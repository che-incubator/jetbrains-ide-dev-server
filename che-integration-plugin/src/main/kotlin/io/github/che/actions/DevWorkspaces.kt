/*
 * Copyright (c) 2025 Red Hat, Inc.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *   Red Hat, Inc. - initial API and implementation
 */
package io.github.che.actions

import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import io.kubernetes.client.custom.V1Patch
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.util.generic.dynamic.DynamicKubernetesApi
import io.kubernetes.client.util.generic.options.PatchOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Helper for applying a local devfile (annotation) and restarting a DevWorkspace.
 * This class interacts with the Kubernetes API to manage DevWorkspace custom resources.
 *
 * - CRD group: workspace.devfile.io
 * - version: v1alpha2
 * - resource: devworkspaces
 *
 * @param apiClient The Kubernetes [ApiClient] used to communicate with the Kubernetes cluster.
 */
class DevWorkspaces(apiClient: ApiClient) {

    private val mapper = createObjectMapper()
    private val genericApi = DynamicKubernetesApi(
        API_GROUP,
        API_VERSION,
        RESOURCE_PLURAL,
        apiClient
    )

    private val coreApi = CoreV1Api(apiClient)

    companion object {
        private const val API_GROUP = "workspace.devfile.io"
        private const val API_VERSION = "v1alpha2"
        private const val RESOURCE_PLURAL = "devworkspaces"
    }
    
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
            mapper.registerModule(kotlinModule as com.fasterxml.jackson.databind.Module)
        } catch (e: Exception) {
            thisLogger().warn("Failed to register Kotlin module, continuing without it", e)
        }
        return mapper
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
    suspend fun applyLocalDevfile(namespace: String, name: String, devfileContent: String): Boolean {
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
     * Restarts the DevWorkspace by deleting its pod(s). 
     * The DevWorkspace controller will notice the pod is gone and immediately start
     * a new one (because spec.started is still true). 
     * Since we update the devfile annotation just before, the new pod will be created
     * with the updated configuration.
     *
     * This operation also exits the remote IDE to trigger a JetBrains Gateway reconnection.
     *
     * @param namespace The namespace of the DevWorkspace.
     * @param name The name of the DevWorkspace.
     * @return `true` if the restart was successfully initiated, `false` otherwise.
     */
    suspend fun restartWorkspace(namespace: String, name: String): Boolean {
        return try {
            withContext(Dispatchers.IO) {
                thisLogger().info("Restarting DevWorkspace $namespace/$name by deleting its pods")

                val labelSelector = "controller.devfile.io/devworkspace_name=$name"
                val podList = coreApi.listNamespacedPod(namespace)
                    .labelSelector(labelSelector)
                    .execute()
                thisLogger().info("found ${podList.items.size} workspace pod(s)")

                podList.items
                    .mapNotNull { it.metadata?.name }
                    .map { podName ->
                        deletePod(podName, namespace)
                    }
                    .all { result -> result }
            }
            true
        } catch (t: Throwable) {
            thisLogger().error("Failed to restart DevWorkspace $namespace/$name", t)
            false
        }
    }

    private fun deletePod(podName: String, namespace: String): Boolean {
        return try {
            thisLogger().info("Deleting pod $podName")
            coreApi.deleteNamespacedPod(podName, namespace)
                .execute()
            true
        } catch (t: Throwable) {
            thisLogger().error("Failed to delete pod $podName in namespace $namespace", t)
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
