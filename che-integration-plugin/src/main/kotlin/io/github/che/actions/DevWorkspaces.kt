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

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
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

    private val mapper = jacksonObjectMapper()
    private val customApi = CustomObjectsApi(apiClient)

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
                patch(namespace, name, body)
            }
            true
        } catch (t: Throwable) {
            thisLogger().error("Failed to apply local devfile to $namespace/$name", t)
            false
        }
    }

    /**
     * Restarts the DevWorkspace by toggling its `spec.started` field from `false` to `true`.
     * Some Kubernetes clusters may react to a single replace; this implementation explicitly
     * sets `started` to `false` and then to `true` to ensure a restart.
     *
     * This operation runs on the IO dispatcher.
     *
     * @param namespace The namespace of the DevWorkspace.
     * @param name The name of the DevWorkspace.
     * @return `true` if the workspace restart was successfully initiated, `false` otherwise.
     */
    suspend fun restartWorkspace(namespace: String, name: String): Boolean {
        return try {
            withContext(Dispatchers.IO) {
                // set started = false
                val stopOps = listOf(
                    mapOf("op" to "replace", "path" to "/spec/started", "value" to false)
                )
                val stopBody: Any = mapper.valueToTree(stopOps)
                patch(namespace, name, stopBody)

                // small delay might be needed in a real environment; callers can wait if required.
                // set started = true
                val startOps = listOf(
                    mapOf("op" to "replace", "path" to "/spec/started", "value" to true)
                )
                val startBody: Any = mapper.valueToTree(startOps)
                patch(namespace, name, startBody)
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
            val mapper = jacksonObjectMapper()
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
        } catch (t: Throwable) {
            thisLogger().error("Unexpected error while patching DevWorkspace $namespace/$name", t)
            throw t
        }
    }
}
