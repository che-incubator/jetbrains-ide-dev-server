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
package io.github.che.devworkspace

import com.intellij.openapi.diagnostic.thisLogger
import io.kubernetes.client.util.Config
import io.kubernetes.client.openapi.ApiClient
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Provides utilities for obtaining Kubernetes API client and DevWorkspace context information.
 * This object is responsible for resolving the Kubernetes API client, as well as the
 * current DevWorkspace name and namespace, typically from environment variables.
 */
object DevWorkspaceUtils {
    /**
     * Attempts to create a Kubernetes [ApiClient] by checking the following sources in order:
     * 1. The `KUBECONFIG` environment variable.
     * 2. The default kubeconfig file located at `~/.kube/config`.
     * 3. The Kubernetes Java client's default client configuration.
     *
     * @return An initialized [ApiClient] if successful, or `null` if an API client
     *         cannot be created or an error occurs during the process.
     */
    fun createApiClient(): ApiClient? {
        try {
            val kubeconfigPath = getKubeConfigPath()

            return kubeconfigPath?.takeIf { Files.exists(Paths.get(it)) }
                ?.let { Config.fromConfig(it) }
                ?: Config.defaultClient()
        } catch (e: Throwable) {
            thisLogger().warn("Could not load Kubernetes client", e)
            return null
        }
    }

    private fun getKubeConfigPath(): String? {
        return System.getenv("KUBECONFIG")
            ?: File(System.getProperty("user.home"), ".kube/config")
                .takeIf { it.exists() }?.absolutePath
    }

    /**
     * Resolves the current DevWorkspace name for the project.
     *
     * Retrieves the workspace name from the `DEVWORKSPACE_NAME`
     * environment variable.
     *
     * @return The name of the current DevWorkspace as a [String], or `null` if not found.
     */
    fun getCurrentWorkspaceName(): String? {
        return System.getenv("DEVWORKSPACE_NAME")
    }

    /**
     * Resolves the current namespace for the project's DevWorkspace.
     *
     * Retrieves the namespace from the `DEVWORKSPACE_NAMESPACE`
     * environment variable.
     *
     * @return The namespace of the current DevWorkspace as a [String], or `null` if not found.
     */
    fun getCurrentWorkspaceNamespace(): String? {
        return System.getenv("DEVWORKSPACE_NAMESPACE")
    }
}
