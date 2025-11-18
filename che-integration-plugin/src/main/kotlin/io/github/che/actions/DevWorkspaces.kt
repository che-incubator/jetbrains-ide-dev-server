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
import io.kubernetes.client.util.PatchUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/**
 *  Helper for applying a local devfile (annotation) and restarting a DevWorkspace.
 * - CRD group: workspace.devfile.io
 * - version: v1alpha2
 * - resource: devworkspaces
 *
 */
class DevWorkspaces(apiClient: ApiClient) {

    private val log = LoggerFactory.getLogger(DevWorkspaces::class.java)
    private val mapper = jacksonObjectMapper()
    private val customApi = CustomObjectsApi(apiClient)

    /**
     * Apply the given devfile content to the DevWorkspace CR by patching
     * metadata.annotations["che.eclipse.org/local-devfile"] = devfileContent
     *
     * Runs on IO dispatcher (suspend).
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
                doPatch(namespace, name, body)
            }
            true
        } catch (t: Throwable) {
            log.error("Failed to apply local devfile to $namespace/$name", t)
            false
        }
    }

    /**
     * Restart the DevWorkspace by toggling spec. Started false -> true.
     * Some clusters may react to a single replace; here we do false then true.
     *
     * Runs on IO dispatcher (suspend).
     */
    suspend fun restartWorkspace(namespace: String, name: String): Boolean {
        return try {
            withContext(Dispatchers.IO) {
                // set started = false
                val stopOps = listOf(
                    mapOf("op" to "replace", "path" to "/spec/started", "value" to false)
                )
                val stopBody: Any = mapper.valueToTree(stopOps)
                doPatch(namespace, name, stopBody)

                // small delay might be needed in a real environment; callers can wait if required.
                // set started = true
                val startOps = listOf(
                    mapOf("op" to "replace", "path" to "/spec/started", "value" to true)
                )
                val startBody: Any = mapper.valueToTree(startOps)
                doPatch(namespace, name, startBody)
            }
            true
        } catch (t: Throwable) {
            log.error("Failed to restart DevWorkspace $namespace/$name", t)
            false
        }
    }

    /** Low-level JSON-PATCH call using PatchUtils (same pattern as Kubernetes examples). */
    @Throws(ApiException::class)
    private fun doPatch(namespace: String, name: String, body: Any) {
        try {
            // prepare JSON patch payload
            val mapper = jacksonObjectMapper()
            val patchJson = when (body) {
                is String -> body
                else -> mapper.writeValueAsString(body)
            }
            val v1patch = V1Patch(patchJson)

            PatchUtils.patch(
                DevWorkspace::class.java,
                {
                    customApi.patchNamespacedCustomObjectCall(
                        "workspace.devfile.io",
                        "v1alpha2",
                        namespace,
                        "devworkspaces",
                        name,
                        v1patch,
                        null, // pretty
                        null, // dryRun
                        null, // fieldManager
                        null  // fieldValidation
                    )
                },
                V1Patch.PATCH_FORMAT_JSON_PATCH,
                customApi.apiClient
            )
        } catch (e: ApiException) {
            // rethrow to keep existing behavior
            throw e
        } catch (t: Throwable) {
            thisLogger().error("Unexpected error while patching DevWorkspace $namespace/$name", t)
            throw t
        }
    }
}