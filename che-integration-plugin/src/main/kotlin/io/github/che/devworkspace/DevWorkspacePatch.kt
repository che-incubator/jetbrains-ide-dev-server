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

/**
 * Utility class for patching DevWorkspace resources.
 *
 * This class handles applying local devfiles, setting restart annotations, and other
 * patch operations on DevWorkspace resources. The restart marker annotation
 * (`che.eclipse.org/restart-in-progress`) signals the JetBrains Gateway plugin to roll
 * the workspace pod. The Gateway watches the DevWorkspace for this annotation and deletes
 * the pod; on client close it also avoids stopping the DevWorkspace while this marker is set.
 *
 * - CRD group: workspace.devfile.io
 * - version: v1alpha2
 * - resource: devworkspaces
 *
 * @param genericApi the kubernetes api to operate on
 * @param mapper the mapper to create json with
 */
    class DevWorkspacePatch internal constructor(
    private val genericApi: DynamicKubernetesApi,
    private val mapper: ObjectMapper
) {

    /**
     * @param apiClient kubernetes client to use in the dynamic kubernetes api
     */
    constructor(apiClient: ApiClient) : this(
        DynamicKubernetesApi(API_GROUP, API_VERSION, RESOURCE_PLURAL, apiClient),
        createObjectMapper()
    )

    companion object {
        private const val API_GROUP = "workspace.devfile.io"
        private const val API_VERSION = "v1alpha2"
        private const val RESOURCE_PLURAL = "devworkspaces"
        private const val ANNOTATION_KEY = "che.eclipse.org/restart-in-progress"
        private const val ANNOTATION_VALUE = "true"

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
     * Adds the restart marker annotation to the specified DevWorkspace resource.
     * This signals the Gateway plugin to restart the workspace.
     *
     * Uses JSON Merge Patch (RFC 7386) which automatically creates the annotations map if missing.
     *
     * @param name The name of the DevWorkspace.
     * @param namespace The namespace of the DevWorkspace.
     * @return `true` if the annotation was successfully added, `false` otherwise.
     */
    suspend fun applyRestart(namespace: String, name: String): Boolean {
        return try {
            withContext(Dispatchers.IO) {
                val body = mapOf(
                    "metadata" to mapOf(
                        "annotations" to mapOf(
                            ANNOTATION_KEY to ANNOTATION_VALUE
                        )
                    )
                )

                thisLogger().info("Adding annotation $ANNOTATION_KEY to $namespace/$name")
                patch(namespace, name, body, V1Patch.PATCH_FORMAT_JSON_MERGE_PATCH)
            }
            true
        } catch (t: Throwable) {
            thisLogger().error("Failed to add restart DevWorkspace annotation to $namespace/$name", t)
            false
        }
    }

    /**
     * Applies the given devfile content to the DevWorkspace custom resource by patching
     * the `metadata.annotations["che.eclipse.org/local-devfile"]` field.
     *
     * Uses JSON Merge Patch (RFC 7386) which automatically creates the annotations map if missing.
     *
     * This operation runs on the IO dispatcher.
     *
     * @param namespace The namespace of the DevWorkspace.
     * @param name The name of the DevWorkspace.
     * @param devfileContent The content of the devfile to be applied as an annotation.
     * @return `true` if the devfile was successfully applied, `false` otherwise.
     */
    suspend fun applyDevfile(namespace: String, name: String, devfileContent: String): Boolean {
        return try {
            withContext(Dispatchers.IO) {
                val annotationKey = "che.eclipse.org/local-devfile"
                val body = mapOf(
                    "metadata" to mapOf(
                        "annotations" to mapOf(
                            annotationKey to devfileContent
                        )
                    )
                )

                thisLogger().info("Adding annotation $annotationKey to $namespace/$name")
                patch(namespace, name, body, V1Patch.PATCH_FORMAT_JSON_MERGE_PATCH)
            }
            true
        } catch (t: Throwable) {
            thisLogger().error("Failed to apply local devfile to $namespace/$name", t)
            false
        }
    }

    /**
     * Removes the local devfile annotation from the DevWorkspace custom resource.
     * This is used to roll back a devfile application when the restart annotation fails.
     *
     * Uses JSON Patch (RFC 6902) for explicit removal.
     *
     * This operation runs on the IO dispatcher.
     *
     * @param namespace The namespace of the DevWorkspace.
     * @param name The name of the DevWorkspace.
     * @return `true` if the annotation was successfully removed, `false` otherwise.
     */
    suspend fun removeDevfile(namespace: String, name: String): Boolean {
        return try {
            withContext(Dispatchers.IO) {
                val annotationKey = "che.eclipse.org/local-devfile"
                val path = "/metadata/annotations/${escapeJsonPointer(annotationKey)}"

                val ops = listOf(
                    mapOf(
                        "op" to "remove",
                        "path" to path
                    )
                )

                thisLogger().info("Removing annotation $annotationKey from $namespace/$name")
                patch(namespace, name, ops, V1Patch.PATCH_FORMAT_JSON_PATCH)
            }
            true
        } catch (t: Throwable) {
            thisLogger().error("Failed to remove local devfile annotation from $namespace/$name", t)
            false
        }
    }

    /**
     * Performs a patch operation on a DevWorkspace custom resource using GenericKubernetesApi.
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
