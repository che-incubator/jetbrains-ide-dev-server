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
 * as annotations. The template is stored under `che.eclipse.org/pending-devfile-template`, wrapped
 * in a versioned envelope. `spec.template` is deliberately left untouched; consuming the request
 * is left to the Gateway plugin.
 *
 * The whole publication is **one** JSON Patch request, applied atomically by the API server:
 * an optional `test` on `metadata.resourceVersion`, the removal of a stale restart marker, and the
 * two `add`s. A concurrent modification therefore fails the request instead of being overwritten,
 * and an observation can never see the restart request without its payload.
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
        private const val PENDING_TEMPLATE_ANNOTATION_KEY = "che.eclipse.org/pending-devfile-template"
        private const val TEMPLATE_PAYLOAD_VERSION = 1

        /** Kubernetes rejects an object whose annotations exceed 256 KiB in total. */
        private const val MAX_ANNOTATIONS_BYTES = 256 * 1024

        /** Margin for annotations other controllers add between the read and the patch. */
        private const val ANNOTATIONS_HEADROOM_BYTES = 4 * 1024

        /** Length of the restart marker value. The Gateway matches the fixed string `true` exactly. */
        private const val RESTART_VALUE_BYTES = 4

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
     * The workspace is read once, before the user is asked to confirm, so that the size limit is
     * checked before the user is asked to act. Nothing is published here.
     *
     * @param resourceVersion the `metadata.resourceVersion` the confirmation was based on
     */
    internal data class RestartPlan(
        val resourceVersion: String?
    )

    /**
     * Reads the workspace and computes the restart request that the user is asked to confirm.
     *
     * The template the devfile produces is built and the annotation size limit is validated.
     * Nothing is patched here.
     *
     * @param namespace The namespace of the DevWorkspace.
     * @param name The name of the DevWorkspace.
     * @param devfileContent The content of the devfile to apply.
     * @return the resource version read
     * @throws RestartException if the devfile is invalid, too large, or cannot be prepared
     */
    @Throws(RestartException::class)
    suspend fun planRestart(
        namespace: String,
        name: String,
        devfileContent: String
    ): RestartPlan {
        return try {
            withContext(Dispatchers.IO) {
                val devfile = DevfileTemplate.parse(devfileContent)
                val workspace = readWorkspace(namespace, name)
                val currentTemplate = findMap(workspace, "spec", "template")
                val nextTemplate = devfile.toTemplate(currentTemplate)
                ensureAnnotationsFit(workspace, buildTemplatePayload(nextTemplate))
                RestartPlan(resourceVersion = resourceVersionOf(workspace))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: RestartException) {
            throw e
        } catch (e: Exception) {
            throw RestartException(
                RestartException.Reason.RESTART_PREPARATION_FAILED,
                "Could not prepare the restart of workspace $namespace/$name: ${e.message}",
                e
            )
        }
    }

    /**
     * Publishes the restart request and the template payload in one atomic JSON Patch.
     *
     * The workspace is read again and the template is rebuilt from that fresh read, so a change
     * made while the user was confirming is not reverted. The read's `metadata.resourceVersion` is
     * sent as a `test` operation: if the workspace changed in the meantime the API server rejects
     * the patch with a conflict instead of silently overwriting it.
     *
     * This operation runs on the IO dispatcher.
     *
     * @param namespace The namespace of the DevWorkspace.
     * @param name The name of the DevWorkspace.
     * @param devfileContent The content of the devfile to apply.
     * @param expectedResourceVersion the resource version the confirmation was based on, if any
     * @throws RestartException if the devfile cannot be applied or the workspace cannot be patched
     */
    @Throws(RestartException::class)
    suspend fun publishRestart(
        namespace: String,
        name: String,
        devfileContent: String,
        expectedResourceVersion: String?
    ) {
        try {
            withContext(Dispatchers.IO) {
                val devfile = DevfileTemplate.parse(devfileContent)
                val workspace = readWorkspace(namespace, name)
                val payload = buildTemplatePayload(
                    devfile.toTemplate(findMap(workspace, "spec", "template"))
                )
                ensureAnnotationsFit(workspace, payload)
                val patchBody = buildPatchBody(
                    workspace = workspace,
                    payload = payload,
                    restartValue = "true",
                    expectedResourceVersion = expectedResourceVersion
                )

                thisLogger().info("Publishing devfile and restart annotation to $namespace/$name")
                patch(namespace, name, patchBody, V1Patch.PATCH_FORMAT_JSON_PATCH)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: RestartException) {
            throw e
        } catch (e: Exception) {
            throw RestartException(
                RestartException.Reason.PUBLISH_FAILED,
                "Could not apply the devfile to workspace $namespace/$name: ${e.message}",
                e
            )
        }
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
            .filter {
                it.key != PENDING_TEMPLATE_ANNOTATION_KEY
                        && it.key != RESTART_ANNOTATION_KEY
            }
            .sumOf {
                byteSize(it.key) +
                        byteSize(it.value?.toString() ?: "")
            }
        val needed = used +
            byteSize(PENDING_TEMPLATE_ANNOTATION_KEY) +
            byteSize(payload) +
            byteSize(RESTART_ANNOTATION_KEY) +
            RESTART_VALUE_BYTES
        val limit = MAX_ANNOTATIONS_BYTES - ANNOTATIONS_HEADROOM_BYTES
        if (needed > limit) {
            throw RestartException(
                RestartException.Reason.PAYLOAD_TOO_LARGE,
                "The converted template is too large to apply: it needs $needed bytes for the " +
                    "workspace annotations, the limit is $limit bytes."
            )
        }
    }

    private fun byteSize(value: String): Int {
        return value.toByteArray(StandardCharsets.UTF_8).size
    }

    /**
     * Builds the single JSON Patch that publishes the template payload and adds the restart
     * annotation.
     *
     * The operations are applied atomically, so the Gateway never sees the restart request without
     * the content. When a `resourceVersion` is supplied, a `test` op first guards against a
     * concurrent modification. When the DevWorkspace has no annotations map yet, the map is added
     * in one op. When a stale restart marker is present, it is removed and re-added within the same
     * atomic patch (RFC 6902 `add` would replace it anyway), so no observer can see an intermediate
     * state. The Gateway removes both annotations after handling the request, so a later
     * publication is a fresh request.
     */
    private fun buildPatchBody(
        workspace: Map<String, Any?>,
        payload: String,
        restartValue: String,
        expectedResourceVersion: String?
    ): List<Map<String, Any?>> {
        val operations = mutableListOf<Map<String, Any?>>()

        if (expectedResourceVersion != null) {
            operations += mapOf(
                "op" to "test",
                "path" to "/metadata/resourceVersion",
                "value" to expectedResourceVersion
            )
        }

        val annotations = findMap(workspace, "metadata", "annotations")
        if (findMap(workspace, "metadata") == null) {
            throw RestartException(
                RestartException.Reason.PUBLISH_FAILED,
                "The workspace has no metadata and cannot be annotated."
            )
        }
        if (annotations == null) {
            operations += mapOf(
                "op" to "add",
                "path" to "/metadata/annotations",
                "value" to mapOf(
                    PENDING_TEMPLATE_ANNOTATION_KEY to payload,
                    RESTART_ANNOTATION_KEY to restartValue
                )
            )
            return operations
        }

        if (annotations.containsKey(RESTART_ANNOTATION_KEY)) {
            operations += mapOf(
                "op" to "remove",
                "path" to "/metadata/annotations/${escapeJsonPointer(RESTART_ANNOTATION_KEY)}"
            )
        }
        mapOf(
            PENDING_TEMPLATE_ANNOTATION_KEY to payload,
            RESTART_ANNOTATION_KEY to restartValue
        ).forEach { (key, value) ->
            operations += mapOf(
                "op" to "add",
                "path" to "/metadata/annotations/${escapeJsonPointer(key)}",
                "value" to value
            )
        }
        return operations
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
            throw ApiException(
                "Could not read $namespace/$name: ${describe(status?.message, status?.code)}",
                status?.code ?: 0,
                null,
                null
            )
        }
        val workspace = response.`object`
            ?: throw IllegalStateException("Empty response while reading $namespace/$name")
        return mapper.readValue(workspace.raw.toString(), WORKSPACE_TYPE)
    }

    private fun resourceVersionOf(workspace: Map<String, Any?>): String? {
        return findMap(workspace, "metadata")?.get("resourceVersion") as? String
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
     */
    @Throws(ApiException::class)
    private fun patch(namespace: String, name: String, patchBody: Any, patchFormat: String) {
        val v1Patch = V1Patch(serialize(patchBody))

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
            throw ApiException(
                "Could not patch $namespace/$name: ${describe(status?.message, status?.code)}",
                status?.code ?: 0,
                null,
                null
            )
        }
        thisLogger().info("Successfully patched DevWorkspace $namespace/$name")
    }

    /**
     * Turns an API status into a message the user can act on.
     */
    private fun describe(message: String?, code: Int?): String {
        val reason = message ?: "Unknown error"
        return when (code) {
            403 -> "You are not allowed to update this workspace (403): $reason"
            404 -> "The workspace no longer exists (404): $reason"
            409, 422 -> "The workspace changed while you were confirming, please try again " +
                "($code): $reason"
            else -> "$reason (code: ${code ?: 0})"
        }
    }

    private fun serialize(body: Any): String {
        return when (body) {
            is String -> body
            else -> mapper.writeValueAsString(body)
        }
    }
}
