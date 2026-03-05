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
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import io.kubernetes.client.openapi.ApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets

/**
 * Action to restart the workspace from a local devfile.
 *
 * This action reads the local `devfile.yaml` or `.devfile.yaml` file, applies it to the
 * current DevWorkspace, and then restarts the workspace.
 */
class RestartWorkspaceAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project: Project = e.project ?: return

        val devfile = getDevfile(e)
        if (devfile == null) {
            Messages.showErrorDialog(project, "Please select a devfile.yaml or .devfile.yaml to restart the workspace.", "Restart Workspace")
            return
        }

        val devfileContent = try {
            devfile.inputStream.readBytes().toString(StandardCharsets.UTF_8)
        } catch (t: Throwable) {
            thisLogger().warn("Failed to read devfile: ${t.message}", t)
            Messages.showErrorDialog(project, "Failed to read devfile: ${t.message}", "Restart Workspace")
            return
        }

        val workspaceName = DevWorkspaceUtils.getCurrentWorkspaceName()
        val namespace = DevWorkspaceUtils.getCurrentWorkspaceNamespace()
        val apiClient: ApiClient? = DevWorkspaceUtils.getApiClient()

        if (workspaceName.isNullOrBlank()
            || namespace.isNullOrBlank()
            || apiClient == null) {
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

    /**
     * Retrieves the devfile from the current context.
     *
     * Checks the currently edited file or the selected file in the project view.
     *
     * @param e The [AnActionEvent] containing the data context.
     * @return The devfile [VirtualFile] if found, or null otherwise.
     */
    private fun getDevfile(e: AnActionEvent): VirtualFile? {
        val file = e.getData(CommonDataKeys.PSI_FILE)?.virtualFile // edited file
            ?: e.getData(CommonDataKeys.VIRTUAL_FILE) // selected file (project tree)

        return file?.takeIf { it.name in setOf(".devfile.yaml", "devfile.yaml") }
    }
}
