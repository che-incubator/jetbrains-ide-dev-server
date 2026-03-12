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
import io.kubernetes.client.util.generic.dynamic.DynamicKubernetesApi
import io.kubernetes.client.util.generic.options.PatchOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

                // stop workspace
                val stopOp = listOf(
                    mapOf(
                        "op" to "replace",
                        "path" to "/spec/started",
                        "value" to false
                    )
                )
                thisLogger().info("Stopping workspace $namespace/$name")
                patch(namespace, name, stopOp)

                thisLogger().info("Waiting for workspace $namespace/$name to stop...")
                waitUntilPhaseIsNot(setOf("Running", "Starting"), 20000, namespace, name)

                // start workspace
                val startOp = listOf(
                    mapOf(
                        "op" to "replace",
                        "path" to "/spec/started",
                        "value" to true
                    )
                )
                thisLogger().info("Starting workspace $namespace/$name")
                patch(namespace, name, startOp)

                thisLogger().info("Workspace restart initiated for $namespace/$name")
            }
            true
        } catch (t: Throwable) {
            thisLogger().error("Failed to restart DevWorkspace $namespace/$name", t)
            false
        }
    }

    /**
     * Waits for the workspace phase to be something other than the given [phases].
     */
    private suspend fun waitUntilPhaseIsNot(phases: Set<String>, timeoutMs: Long, namespace: String, name: String) {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            val phase = getStatusPhase(namespace, name)
            thisLogger().info("Workspace $namespace/$name is now in phase: $phase")
            if (phase == null
                || phase !in phases) {
                return
            }
            delay(500)
        }
        thisLogger().warn("Timeout waiting for workspace $namespace/$name to change phase (current: ${getStatusPhase(namespace, name)})")
    }

    /**
     * Retrieves the current phase of the DevWorkspace.
     */
    private fun getStatusPhase(namespace: String, name: String): String? {
        return try {
            val response = genericApi.get(namespace, name)
            if (!response.isSuccess) return null

            return response.getObject()
                ?.raw // yaml representation
                ?.getAsJsonObject("status")?.get("phase")
                ?.asString
        } catch (t: Throwable) {
            thisLogger().warn("Could not get phase for $namespace/$name: ${t.message}")
            null
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
