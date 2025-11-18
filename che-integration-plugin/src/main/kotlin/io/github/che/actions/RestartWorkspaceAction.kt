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

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import io.kubernetes.client.openapi.ApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets

class RestartWorkspaceAction : AnAction(
    "Restart Workspace from Local Devfile",
    "Apply local devfile.yaml to DevWorkspace and restart it",
    null
) {
    override fun actionPerformed(e: AnActionEvent) {
        val project: Project = e.project ?: return

        val baseDir = LocalFileSystem.getInstance().findFileByPath(project.basePath!!)

        val devfileVirtual = baseDir?.findChild("devfile.yaml")
            ?: baseDir?.findChild(".devfile.yaml")

        if (devfileVirtual == null) {
            Messages.showErrorDialog(project, "No devfile.yaml or .devfile.yaml found in project root.", "Restart Workspace")
            return
        }

        val devfileContent = try {
            devfileVirtual.inputStream.readBytes().toString(StandardCharsets.UTF_8)
        } catch (t: Throwable) {
            Messages.showErrorDialog(project, "Failed to read devfile.yaml: ${t.message}", "Restart Workspace")
            return
        }

        val workspaceName = DevWorkspacesProvider.getCurrentWorkspaceName()
        val namespace = DevWorkspacesProvider.getCurrentWorkspaceNamespace()
        val apiClient: ApiClient? = DevWorkspacesProvider.getApiClient()

        if (workspaceName.isNullOrBlank() || namespace.isNullOrBlank() || apiClient == null) {
            Messages.showErrorDialog(project, "Could not determine workspace name/namespace or obtain ApiClient. Set DEVWORKSPACE_NAME + DEVWORKSPACE_NAMESPACE or implement resolution logic.", "Restart Workspace")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            val dw = DevWorkspaces(apiClient)

            val applied = try {
                dw.applyLocalDevfile(namespace, workspaceName, devfileContent)
            } catch (_: Throwable) {
                false
            }

            if (!applied) {
                withContext(Dispatchers.Main) {
                    Messages.showErrorDialog(project, "Failed to apply local devfile to DevWorkspace.", "Restart Workspace")
                }
                return@launch
            }

            val restarted = try {
                dw.restartWorkspace(namespace, workspaceName)
            } catch (_: Throwable) {
                false
            }

            withContext(Dispatchers.Main) {
                if (restarted) {
                    Messages.showInfoMessage(project, "Devfile applied and workspace restart initiated.", "Restart Workspace")
                } else {
                    Messages.showErrorDialog(project, "Devfile applied, but restart failed.", "Restart Workspace")
                }
            }
        }
    }
}
