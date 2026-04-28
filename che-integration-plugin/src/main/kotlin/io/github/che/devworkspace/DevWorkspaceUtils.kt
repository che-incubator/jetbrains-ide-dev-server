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

    private val defaultEnvProvider: (String) -> String? = System::getenv

    private val defaultHomeProvider: () -> String = { System.getProperty("user.home") }

    private val defaultClientFactory: (String?) -> ApiClient = { path ->
        if (path != null) {
            Config.fromConfig(path)
        } else {
            Config.defaultClient()
        }
    }

    /**
     * Attempts to create a Kubernetes [ApiClient] by checking the following sources in order:
     * 1. The `KUBECONFIG` environment variable.
     * 2. The default kubeconfig file located at `~/.kube/config`.
     * 3. The Kubernetes Java client's default client configuration.
     *
     * @param envProvider Function to retrieve environment variables (defaults to System::getenv)
     * @param homeProvider Function to retrieve user home directory (defaults to user.home system property)
     * @param clientFactory Function to create ApiClient from kubeconfig path (defaults to Config.fromConfig/defaultClient)
     * @return An initialized [ApiClient] if successful, or `null` if an API client
     *         cannot be created or an error occurs during the process.
     */
    fun createApiClient(
        envProvider: (String) -> String? = defaultEnvProvider,
        homeProvider: () -> String = defaultHomeProvider,
        clientFactory: (String?) -> ApiClient = defaultClientFactory
    ): ApiClient? {
        try {
            val kubeconfigPath = getKubeConfigPath(envProvider, homeProvider)

            return kubeconfigPath?.takeIf { Files.exists(Paths.get(it)) }
                ?.let { clientFactory(it) }
                ?: clientFactory(null)
        } catch (e: Throwable) {
            thisLogger().warn("Could not load Kubernetes client", e)
            return null
        }
    }

    private fun getKubeConfigPath(
        envProvider: (String) -> String?,
        homeProvider: () -> String
    ): String? {
        return envProvider("KUBECONFIG")
            ?: File(homeProvider(), ".kube/config")
                .takeIf { it.exists() }?.absolutePath
    }

    /**
     * Resolves the current DevWorkspace name for the project.
     *
     * Retrieves the workspace name from the `DEVWORKSPACE_NAME`
     * environment variable.
     *
     * @param envProvider Function to retrieve environment variables (defaults to System::getenv)
     * @return The name of the current DevWorkspace as a [String], or `null` if not found.
     */
    fun getCurrentWorkspaceName(
        envProvider: (String) -> String? = defaultEnvProvider
    ): String? {
        return envProvider("DEVWORKSPACE_NAME")
    }

    /**
     * Resolves the current namespace for the project's DevWorkspace.
     *
     * Retrieves the namespace from the `DEVWORKSPACE_NAMESPACE`
     * environment variable.
     *
     * @param envProvider Function to retrieve environment variables (defaults to System::getenv)
     * @return The namespace of the current DevWorkspace as a [String], or `null` if not found.
     */
    fun getCurrentWorkspaceNamespace(
        envProvider: (String) -> String? = defaultEnvProvider
    ): String? {
        return envProvider("DEVWORKSPACE_NAMESPACE")
    }
}
