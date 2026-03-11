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
import com.intellij.openapi.diagnostic.thisLogger
import io.kubernetes.client.custom.V1Patch
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.apis.CustomObjectsApi
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
    private val customApi = CustomObjectsApi(apiClient)
    
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
     * Restarts the DevWorkspace by patching spec.started to false then true.
     * This approach is used by che-code and works despite controller restrictions.
     *
     * Based on: https://github.com/che-incubator/che-code/blob/main/code/extensions/che-api/src/impl/k8s-workspace-service-impl.ts
     *
     * This operation runs on the IO dispatcher.
     *
     * @param namespace The namespace of the DevWorkspace.
     * @param name The name of the DevWorkspace.
     * @return `true` if the restart was successfully initiated, `false` otherwise.
     */
    suspend fun restartWorkspace(namespace: String, name: String): Boolean {
        return try {
            withContext(Dispatchers.IO) {
                thisLogger().info("Restarting DevWorkspace $namespace/$name")

                // Stop the workspace using JSON Patch
                val stopOps = listOf(
                    mapOf("op" to "replace", "path" to "/spec/started", "value" to false)
                )
                thisLogger().info("Stopping workspace $namespace/$name")
                patch(namespace, name, stopOps)

                // Start it again
                val startOps = listOf(
                    mapOf("op" to "replace", "path" to "/spec/started", "value" to true)
                )
                thisLogger().info("Starting workspace $namespace/$name")
                patch(namespace, name, startOps)

                thisLogger().info("Workspace restart initiated for $namespace/$name")
            }
            true
        } catch (t: Throwable) {
            thisLogger().error("Failed to restart DevWorkspace $namespace/$name", t)
            false
        }
    }

    /**
     * Performs a patch operation on a DevWorkspace custom resource.
     *
     * This private helper method serializes the provided `body` into a JSON patch
     * and applies it to the specified DevWorkspace.
     *
     * @param namespace The namespace of the DevWorkspace.
     * @param name The name of the DevWorkspace.
     * @param body The patch body, which can be a String or any object that can be serialized to JSON.
     * @throws ApiException if the Kubernetes API call fails.
     * @throws Throwable for any other unexpected errors during the patching process.
     */
    @Throws(ApiException::class)
    private fun patch(namespace: String, name: String, body: Any) {
        try {
            val patchJson = when (body) {
                is String -> body
                else -> mapper.writeValueAsString(body)
            }
            val v1patch = V1Patch(patchJson)

            customApi.patchNamespacedCustomObject(
                "workspace.devfile.io",
                "v1alpha2",
                namespace,
                "devworkspaces",
                name,
                v1patch
                // pretty
                // dryRun
                // fieldManager
            )
            thisLogger().info("Patched resource $namespace/$name to content $body")
        } catch (t: Throwable) {
            thisLogger().error("Could not patch $namespace/$name", t)
            throw t
        }
    }
}
