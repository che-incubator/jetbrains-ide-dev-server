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

import io.kubernetes.client.util.Config
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.Configuration
import java.io.File

object DevWorkspacesProvider {
    /**
     * Attempt to create an ApiClient from:
     * - KUBECONFIG env var, or
     * - ~/.kube/config, or
     * - defaultClient()
     */
    fun getApiClient(): ApiClient? {
        try {
            val kubeconfigPath = System.getenv("KUBECONFIG")
                ?: File(System.getProperty("user.home"), ".kube/config").takeIf { it.exists() }?.absolutePath

            val client = if (!kubeconfigPath.isNullOrEmpty()) {
                Config.fromConfig(kubeconfigPath)
            } else {
                Config.defaultClient()
            }
            // optionally tweak client settings:
            // client.isVerifyingSsl = false
            Configuration.setDefaultApiClient(client)
            return client
        } catch (_: Throwable) {
            return null
        }
    }

    /**
     * Resolve the current DevWorkspace name for this project.
     * Replace this with real resolution logic (project-level settings / remote session metadata).
     */
    fun getCurrentWorkspaceName(): String? {
        return System.getenv("DEVWORKSPACE_NAME")
    }

    /**
     * Resolve the current namespace for this project.
     * Replace with actual logic.
     */
    fun getCurrentWorkspaceNamespace(): String? {
        return System.getenv("DEVWORKSPACE_NAMESPACE")
    }
}
