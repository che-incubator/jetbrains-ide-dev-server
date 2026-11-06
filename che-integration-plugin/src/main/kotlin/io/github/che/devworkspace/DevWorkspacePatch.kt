/*
 * Copyright (c) 2026 Red Hat, Inc.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *   Red Hat, Inc. - initial API and implementation
 */
package io.github.che.devworkspace

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.Module
import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.openapi.diagnostic.thisLogger
import io.kubernetes.client.custom.V1Patch
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.util.generic.dynamic.DynamicKubernetesApi
import io.kubernetes.client.util.generic.options.PatchOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import java.util.concurrent.CancellationException

/**
 * Patches DevWorkspace resources.
 *
 * The only operation applied here is the restart request: the template the edited devfile
 * produces and the restart marker annotation (`che.eclipse.org/restart-in-progress`) are written
 * as annotations in one patch. The template is stored under
 * `che.eclipse.org/pending-devfile-template`, wrapped in a versioned envelope. `spec.template` is
 * deliberately left untouched.
 *
 * The Gateway plugin watches the restart annotation, closes the IDE, stops the workspace, applies
 * the pending template to `spec.template` while the workspace is stopped, and starts the workspace
 * again. Applying the template only while stopped keeps the DevWorkspace Operator from rolling the
 * pod on its own, which would kill the IDE before the Gateway can close it.
 *
 * Both annotations are sent as one JSON Patch request so that the API server applies them
 * atomically: the restart request is never observable without the template payload.
 *
 * - CRD group: workspace.devfile.io
 * - version: v1alpha2
 * - resource: devworkspaces
 *
 * @param genericApi the kubernetes api to operate on
 * @param mapper the mapper used to read the DevWorkspace and write the patch
 */
internal class DevWorkspacePatch(
    private val genericApi: DynamicKubernetesApi,
    private val mapper: ObjectMapper
) {

    companion object {
        private const val API_GROUP = "workspace.devfile.io"
        private const val API_VERSION = "v1alpha2"
        private const val RESOURCE_PLURAL = "devworkspaces"
        private const val RESTART_ANNOTATION_KEY = "che.eclipse.org/restart-in-progress"
        private const val RESTART_ANNOTATION_VALUE = "true"
        private const val PENDING_TEMPLATE_ANNOTATION_KEY = "che.eclipse.org/pending-devfile-template"
        private const val TEMPLATE_PAYLOAD_VERSION = 1

        /** Kubernetes rejects an object whose annotations exceed 256 KiB in total. */
        private const val MAX_ANNOTATIONS_BYTES = 256 * 1024

        /** Room for the restart annotation and for escaping differences of the API server. */
        private const val ANNOTATIONS_HEADROOM_BYTES = 4 * 1024

        private val WORKSPACE_TYPE = object : TypeReference<Map<String, Any?>>() {}

        /**
         * Creates an ObjectMapper using the IDE's Jackson classes and registers the Kotlin module.
         * This avoids classloader conflicts by using IDE's Jackson core classes.
         *
         * @return [ObjectMapper]
         */
        private fun createObjectMapper(): ObjectMapper {
            val mapper = ObjectMapper()
            try {
                // avoid jackson instantiation issues
                val kotlinModuleClass = Class.forName("com.fasterxml.jackson.module.kotlin.KotlinModule")
                val kotlinModule = kotlinModuleClass.getDeclaredConstructor().newInstance()
                mapper.registerModule(kotlinModule as Module)
            } catch (e: Exception) {
                thisLogger().warn("Failed to register Kotlin module, continuing without it", e)
            }
            return mapper
        }

        /**
         * Escapes a string for use in a JSON Pointer path according to RFC 6901.
         * - "~" is replaced with "~0"
         * - "/" is replaced with "~1"
         *
         * @param key The string to escape
         * @return The escaped string safe for use in JSON Pointer paths
         */
        private fun escapeJsonPointer(key: String): String {
            return key.replace("~", "~0").replace("/", "~1")
        }
    }

    /**
     * @param apiClient kubernetes client to use in the dynamic kubernetes api
     */
    constructor(apiClient: ApiClient) : this(
        DynamicKubernetesApi(API_GROUP, API_VERSION, RESOURCE_PLURAL, apiClient),
        createObjectMapper()
    )

    /**
     * The restart request prepared for confirmation.
     *
     * The workspace is read once, before the user is asked to confirm, so that the dialog can say
     * whether the local devfile changes anything. [payload] is the exact annotation value that
     * [publishRestart] sends once the user confirms.
     *
     * @param payload the annotation value carrying the devfile template
     * @param hasChanges whether the template differs from the one the workspace is running
     */
    internal data class RestartPlan(
        val payload: String,
        val hasChanges: Boolean
    )

    /**
     * Reads the workspace and computes the restart request that the user is asked to confirm.
     *
     * The template the devfile produces is compared with the one the workspace is running, so the
     * caller can tell the user whether the restart applies changes or not. Nothing is patched
     * here.
     *
     * This operation runs on the IO dispatcher.
     *
     * @param namespace The namespace of the DevWorkspace.
     * @param name The name of the DevWorkspace.
     * @param devfileContent The content of the devfile to apply.
     * @return the payload to publish and whether the devfile changes the workspace
     * @throws RestartException if the devfile is invalid or the restart cannot be prepared
     */
    @Throws(RestartException::class)
    suspend fun planRestart(
        namespace: String,
        name: String,
        devfileContent: String
    ): RestartPlan {
        return try {
            val devfile = DevfileTemplate.parse(devfileContent)
            withContext(Dispatchers.IO) {
                val workspace = readWorkspace(namespace, name)
                val currentTemplate = findMap(workspace, "spec", "template")
                val nextTemplate = devfile.toTemplate(currentTemplate)
                RestartPlan(
                    payload = buildTemplatePayload(nextTemplate),
                    hasChanges = currentTemplate == null
                            || !TemplateEquality.isEqual(nextTemplate, currentTemplate)
                )
            }
        } catch (e: DevfileValidationException) {
            thisLogger().warn("Devfile cannot be applied to $namespace/$name: ${e.message}")
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            thisLogger().error("Failed to plan the restart of $namespace/$name", t)
            throw RestartException(
                RestartException.Reason.RESTART_PREPARATION_FAILED,
                "Could not prepare the restart of workspace $namespace/$name.",
                t
            )
        }
    }

    /**
     * Publishes the restart request and the template payload in one patch.
     *
     * The workspace is read again so that a restart annotation left behind while the user was
     * confirming is removed first: the Gateway only treats a new annotation as a request.
     *
     * This operation runs on the IO dispatcher.
     *
     * @param namespace The namespace of the DevWorkspace.
     * @param name The name of the DevWorkspace.
     * @param payload The annotation value computed by [planRestart].
     * @throws RestartException if the devfile cannot be applied or the workspace cannot be patched
     */
    @Throws(RestartException::class)
    suspend fun publishRestart(
        namespace: String,
        name: String,
        payload: String
    ) {
        try {
            withContext(Dispatchers.IO) {
                val workspace = readWorkspace(namespace, name)
                removeStaleRestartAnnotation(namespace, name, workspace)
                ensureAnnotationsFit(workspace, payload)
                val patchBody = buildPatchBody(workspace, payload)

                thisLogger().info("Publishing devfile and restart annotation to $namespace/$name")
                patch(namespace, name, patchBody, V1Patch.PATCH_FORMAT_JSON_PATCH)
            }
        } catch (e: DevfileValidationException) {
            thisLogger().warn("Devfile cannot be applied to $namespace/$name: ${e.message}")
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            thisLogger().error("Failed to publish the restart of $namespace/$name", t)
            throw RestartException(
                RestartException.Reason.PUBLISH_FAILED,
                "Could not apply the devfile to workspace $namespace/$name.",
                t
            )
        }
    }

    /**
     * Removes a restart annotation that predates this request.
     *
     * The Gateway only treats an annotation that appears as a restart request. A leftover
     * annotation (for example from an attempt made while the Gateway was not watching) would
     * otherwise swallow this request. Removing it first makes the annotation added by
     * [buildPatchBody] an absent-to-present transition again.
     */
    private fun removeStaleRestartAnnotation(
        namespace: String,
        name: String,
        workspace: Map<String, Any?>
    ) {
        val annotations = findMap(workspace, "metadata", "annotations") ?: return
        if (!annotations.containsKey(RESTART_ANNOTATION_KEY)) {
            return
        }
        thisLogger().info("Removing stale restart annotation from $namespace/$name")
        patch(
            namespace,
            name,
            listOf(
                mapOf(
                    "op" to "remove",
                    "path" to "/metadata/annotations/${escapeJsonPointer(RESTART_ANNOTATION_KEY)}"
                )
            ),
            V1Patch.PATCH_FORMAT_JSON_PATCH
        )
    }

    /**
     * Wraps the `spec.template` the devfile produces in a versioned envelope, so the Gateway can
     * reject a payload it does not understand.
     */
    private fun buildTemplatePayload(template: Map<String, Any?>): String {
        val payload = mapOf(
            "version" to TEMPLATE_PAYLOAD_VERSION,
            "template" to template
        )
        return mapper.writeValueAsString(payload)
    }

    /**
     * Fails before touching the DevWorkspace when the payload would push its annotations over the
     * Kubernetes limit (256 KiB in total). Checking here gives the user a clear message instead of
     * an opaque 422 from the API server.
     */
    private fun ensureAnnotationsFit(workspace: Map<String, Any?>, payload: String) {
        val annotations = findMap(workspace, "metadata", "annotations") ?: emptyMap()
        val used = annotations.entries
            .filter { it.key != PENDING_TEMPLATE_ANNOTATION_KEY }
            .sumOf { byteSize(it.key) + byteSize(it.value?.toString() ?: "") }
        val needed = used +
            byteSize(PENDING_TEMPLATE_ANNOTATION_KEY) +
            byteSize(payload) +
            byteSize(RESTART_ANNOTATION_KEY) +
            byteSize(RESTART_ANNOTATION_VALUE)
        val limit = MAX_ANNOTATIONS_BYTES - ANNOTATIONS_HEADROOM_BYTES
        if (needed > limit) {
            throw DevfileValidationException(
                "The devfile is too large to apply: it needs $needed bytes for the workspace " +
                    "annotations, the limit is $limit bytes."
            )
        }
    }

    private fun byteSize(value: String): Int {
        return value.toByteArray(StandardCharsets.UTF_8).size
    }

    /**
     * Builds the JSON Patch that publishes the template payload and adds the restart annotation.
     *
     * They are set in the same request so that the Gateway never sees the restart request without
     * the content. When the DevWorkspace has no annotations map yet, the map is added first.
     */
    private fun buildPatchBody(
        workspace: Map<String, Any?>,
        payload: String
    ): List<Map<String, Any?>> {
        val annotations = mapOf(
            PENDING_TEMPLATE_ANNOTATION_KEY to payload,
            RESTART_ANNOTATION_KEY to RESTART_ANNOTATION_VALUE
        )
        if (findMap(workspace, "metadata", "annotations") == null) {
            return listOf(
                mapOf(
                    "op" to "add",
                    "path" to "/metadata/annotations",
                    "value" to annotations
                )
            )
        }
        return annotations.map { (key, value) ->
            mapOf(
                "op" to "add",
                "path" to "/metadata/annotations/${escapeJsonPointer(key)}",
                "value" to value
            )
        }
    }

    /**
     * Reads the DevWorkspace that is patched.
     *
     * @throws ApiException if the Kubernetes API call fails.
     */
    @Throws(ApiException::class)
    private fun readWorkspace(namespace: String, name: String): Map<String, Any?> {
        val response = genericApi.get(namespace, name)
        if (!response.isSuccess) {
            val status = response.status
            val message = status?.message ?: "Unknown error"
            val code = status?.code ?: 0
            throw ApiException("Could not read $namespace/$name: $message (code: $code)", code, null, null)
        }
        val workspace = response.`object`
            ?: throw IllegalStateException("Empty response while reading $namespace/$name")
        return mapper.readValue(workspace.raw.toString(), WORKSPACE_TYPE)
    }

    /**
     * Returns the nested map at [path], or `null` when any segment is missing or not a map.
     */
    private fun findMap(root: Map<String, Any?>, vararg path: String): Map<String, Any?>? {
        var current: Any? = root
        for (segment in path) {
            current = (current as? Map<*, *>)?.get(segment)
        }
        return (current as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }
    }

    /**
     * Performs a patch operation on a DevWorkspace custom resource.
     *
     * @param namespace The namespace of the DevWorkspace.
     * @param name The name of the DevWorkspace.
     * @param patchBody The patch body, which can be a String or any object that can be serialized to JSON.
     * @param patchFormat The patch format (JSON Patch, Strategic Merge Patch, etc.)
     * @throws ApiException if the Kubernetes API call fails.
     * @throws Throwable for any other unexpected errors during the patching process.
     */
    @Throws(ApiException::class)
    private fun patch(namespace: String, name: String, patchBody: Any, patchFormat: String) {
        try {
            val patchString = toString(patchBody)
            val v1Patch = V1Patch(patchString)

            thisLogger().info("Patching DevWorkspace $namespace/$name using $patchFormat")
            val response = genericApi.patch(
                namespace,
                name,
                patchFormat,
                v1Patch,
                PatchOptions()
            )

            if (!response.isSuccess) {
                val status = response.status
                val message = status?.message ?: "Unknown error"
                val code = status?.code ?: 0
                throw ApiException(
                    "Could not patch $namespace/$name: $message (code: $code)",
                    code,
                    null,
                    null
                )
            }

            thisLogger().info("Successfully patched DevWorkspace $namespace/$name")
        } catch (t: Throwable) {
            thisLogger().error("Could not patch $namespace/$name", t)
            throw t
        }
    }

    private fun toString(body: Any): String? {
        return when (body) {
            is String -> body
            else -> mapper.writeValueAsString(body)
        }
    }
}
