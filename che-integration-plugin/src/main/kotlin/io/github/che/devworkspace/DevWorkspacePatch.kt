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
     * @param apiClient kubernetes client to use in the dynamis kubernetes api
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
    }

    /**
     * Adds the restart marker annotation to the specified DevWorkspace resource.
     * This signals the Gateway plugin to restart the workspace.
     *
     * @param name The name of the DevWorkspace.
     * @param namespace The namespace of the DevWorkspace.
     * @return `true` if the annotation was successfully added, `false` otherwise.
     */
    suspend fun applyRestart(namespace: String, name: String): Boolean {
        return try {
            withContext(Dispatchers.IO) {
                val path = "/metadata/annotations/${ANNOTATION_KEY.replace("/", "~1")}"

                val ops = listOf(
                    mapOf(
                        "op" to "add",
                        "path" to path,
                        "value" to ANNOTATION_VALUE
                    )
                )

                val body: Any = mapper.valueToTree(ops)
                thisLogger().info("Adding restart DevWorkspace annotation to $namespace/$name")
                patch(namespace, name, body)
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
                val jsonPointerKey = "che.eclipse.org/local-devfile"
                val escapedPointer = jsonPointerKey.replace("/", "~1")
                val path = "/metadata/annotations/$escapedPointer"

                val ops = listOf(
                    mapOf(
                        "op" to "add",
                        "path" to path,
                        "value" to devfileContent
                    )
                )

                val body: Any = mapper.valueToTree(ops)
                thisLogger().debug("Patched che.eclipse.org/local-devfile $name to $body")
                patch(namespace, name, body)
            }
            true
        } catch (t: Throwable) {
            thisLogger().error("Failed to apply local devfile to $namespace/$name", t)
            false
        }
    }

    /**
     * Performs a patch operation on a DevWorkspace custom resource using GenericKubernetesApi.
     *
     * This method uses GenericKubernetesApi with DynamicKubernetesObject to send a patch
     * with the correct Content-Type header for JSON Patch (RFC 6902): application/json-patch+json.
     *
     * @param namespace The namespace of the DevWorkspace.
     * @param name The name of the DevWorkspace.
     * @param stringOrJson The patch body, which can be a String or any object that can be serialized to JSON.
     * @throws ApiException if the Kubernetes API call fails.
     * @throws Throwable for any other unexpected errors during the patching process.
     */
    @Throws(ApiException::class)
    private fun patch(namespace: String, name: String, stringOrJson: Any) {
        try {
            val patch = toString(stringOrJson)
            thisLogger().info("Patching resource $namespace/$name with content $patch")
            val v1Patch = V1Patch(patch)
            val response = genericApi.patch(
                namespace,
                name,
                V1Patch.PATCH_FORMAT_JSON_PATCH,
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

            thisLogger().info("Successfully patched resource $namespace/$name, response=$response")
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
