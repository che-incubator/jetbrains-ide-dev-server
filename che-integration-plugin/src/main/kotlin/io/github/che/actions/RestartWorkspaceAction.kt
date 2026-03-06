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

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import io.kubernetes.client.openapi.ApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets

/**
 * An action that allows restarting a DevWorkspace by applying the local `devfile.yaml`.
 * This action is typically available from the main menu or context menus and is enabled
 * when a project is open and a devfile can be identified.
 */
class RestartWorkspaceAction : AnAction() {

    /**
     * Specifies that the update method should run on a background thread (BGT)
     * to allow safe access to VirtualFile and PSI data structures.
     */
    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }

    /**
     * Updates the state of the action. This method is called by the system to determine
     * whether the action should be visible and enabled in the current context.
     *
     * The action is enabled only if a project is open and a `devfile.yaml` or
     * `.devfile.yaml` file is currently selected in the editor or project view.
     *
     * @param e The [AnActionEvent] containing information about the current context.
     */
    override fun update(e: AnActionEvent) {
        // Set the availability based on whether a project is open and a devfile is selected.
        val project = e.project
        val presentation = e.presentation
        
        if (project == null) {
            presentation.isEnabledAndVisible = false
            return
        }

        val devfile = getDevfile(e)
        val isVisible = devfile != null
        
        // Debug logging (can be removed later)
        if (!isVisible) {
            thisLogger().debug("RestartWorkspaceAction: Not visible. PSI_FILE=${e.getData(CommonDataKeys.PSI_FILE)?.name}, " +
                    "VIRTUAL_FILE=${e.getData(CommonDataKeys.VIRTUAL_FILE)?.name}, " +
                    "EDITOR=${e.getData(CommonDataKeys.EDITOR) != null}")
        }
        
        presentation.isEnabledAndVisible = isVisible
    }

    /**
     * Performs the action logic to restart a DevWorkspace.
     *
     * This method attempts to:
     * 1. Identify the project and a relevant `devfile.yaml` or `.devfile.yaml`.
     * 2. Read the content of the identified devfile.
     * 3. Obtain the current workspace name, namespace, and Kubernetes API client.
     * 4. Asynchronously apply the local devfile to the DevWorkspace and then restart it.
     * 5. Provide user feedback through dialog messages for success or failure.
     *
     * @param e The [AnActionEvent] which contains information about the current context,
     *          such as the project and selected files.
     */
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
     * Retrieves the devfile (`.devfile.yaml` or `devfile.yaml`) from the current action event context.
     *
     * This function checks multiple sources in order:
     * 1. The PSI file currently being edited (for editor context menu)
     * 2. The file associated with the current editor document (for editor context menu)
     * 3. The selected file(s) in project view (VIRTUAL_FILE or VIRTUAL_FILE_ARRAY)
     * 4. Selected items from PlatformDataKeys (for project view)
     *
     * @param e The [AnActionEvent] providing access to the current editor and project selection data.
     * @return The [VirtualFile] representing the devfile if found, otherwise `null`.
     */
    private fun getDevfile(e: AnActionEvent): VirtualFile? {
        val devfileNames = setOf(".devfile.yaml", "devfile.yaml")
        
        return getFromPsiFile(e, devfileNames)
            ?: getFromEditor(e, devfileNames)
            ?: getFromVirtualFile(e, devfileNames)
    }

    private fun getFromVirtualFile(e: AnActionEvent, devfileNames: Set<String>): VirtualFile? {
        val virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
        if (virtualFile != null
            && virtualFile.name in devfileNames) {
            return virtualFile
        }

        val virtualFiles = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
        if (!virtualFiles.isNullOrEmpty()) {
            val devfile = virtualFiles.firstOrNull { it.name in devfileNames }
            if (devfile != null) {
                return devfile
            }
        }

        val selectedItems = e.getData(PlatformDataKeys.SELECTED_ITEMS)
        return selectedItems?.asSequence()
            ?.mapNotNull { item ->
                when (item) {
                    is PsiFile -> item.virtualFile
                    is VirtualFile -> item
                    else -> null
                }
            }
            ?.firstOrNull { it.name in devfileNames }
    }

    private fun getFromPsiFile(e: AnActionEvent, devfileNames: Set<String>): VirtualFile? {
        val psiFile: PsiFile? = e.getData(CommonDataKeys.PSI_FILE)
        val psiVirtualFile = psiFile?.virtualFile
        return if (psiVirtualFile != null
            && psiVirtualFile.name in devfileNames
        ) {
            psiVirtualFile
        } else {
            null
        }

    }

    private fun getFromEditor(
        e: AnActionEvent,
        devfileNames: Set<String>
    ): VirtualFile? {
        val editor: Editor? = e.getData(CommonDataKeys.EDITOR)
        return editor?.document?.let { doc ->
            FileDocumentManager.getInstance()
                .getFile(doc)?.takeIf { it.name in devfileNames }
        }
    }

}
