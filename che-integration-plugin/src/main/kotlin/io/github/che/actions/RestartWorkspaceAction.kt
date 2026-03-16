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
import com.intellij.openapi.application.ApplicationManager
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
 * An action that allows restarting a DevWorkspace by applying a local devfile.
 * The devfile is identified by content (schema: schemaVersion + metadata), so any filename
 * is supported. The action is enabled when a project is open and the file in the editor
 * or selected in the project tree is a devfile.
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
     * The action is enabled only if a project is open and the file in the editor or
     * selected in the project view is a devfile (identified by schema content).
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
     * 1. Identify the project and the devfile (from editor or selection, by content).
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
            Messages.showErrorDialog(project, "Please select or open a devfile in the editor or project tree to restart the workspace.", "Restart Workspace")
            return
        }

        val devfileContent = try {
            devfile.inputStream.readBytes().toString(StandardCharsets.UTF_8)
        } catch (t: Throwable) {
            thisLogger().warn("Failed to read devfile: ${t.message}", t)
            Messages.showErrorDialog(project,
                "Failed to read devfile: ${t.message}",
                "Restart Workspace")
            return
        }

        val workspaceName = DevWorkspaceUtils.getCurrentWorkspaceName()
        val namespace = DevWorkspaceUtils.getCurrentWorkspaceNamespace()
        val apiClient: ApiClient? = DevWorkspaceUtils.getApiClient()

        if (workspaceName.isNullOrBlank()
            || namespace.isNullOrBlank()
            || apiClient == null) {
            Messages.showErrorDialog(project,
                "Could not determine workspace name/namespace or failed to connect to the cluster.",
                "Restart Workspace")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            val dw = DevWorkspaces(apiClient)

            val modified = modifyDevfile(dw, namespace, workspaceName, devfileContent)
            if (!modified) {
                withContext(Dispatchers.Main) {
                    Messages.showErrorDialog(project,
                        "Failed to apply local devfile to DevWorkspace.",
                        "Restart Workspace")
                }
                return@launch
            }

            val (restarted, errorMessage) = restartWorkspace(dw, namespace, workspaceName)
            withContext(Dispatchers.Main) {
                if (restarted) {
                    Messages.showInfoMessage(project,
                        "Workspace restart initiated successfully.\n\n" +
                        "The workspace should restart automatically with the updated devfile.",
                        "Restarting Workspace"
                    )
                    ApplicationManager.getApplication().exit(true, true, false)
                } else {
                    val message = if (errorMessage != null) {
                        "Failed to initiate workspace restart.\n\nError: $errorMessage"
                    } else {
                        "Failed to initiate workspace restart."
                    }
                    Messages.showErrorDialog(project, message, "Restart Workspace")
                }
            }
        }
    }

    private suspend fun restartWorkspace(
        dw: DevWorkspaces,
        namespace: String,
        workspaceName: String
    ): Pair<Boolean, String?> {
        return try {
            val success = dw.restartWorkspace(namespace, workspaceName)
            Pair(success, null)
        } catch (t: Throwable) {
            thisLogger().warn("Could not restart DevWorkspace.", t)
            val errorMessage = t.message ?: "Unknown error"
            Pair(false, errorMessage)
        }
    }

    private suspend fun modifyDevfile(
        dw: DevWorkspaces,
        namespace: String,
        workspaceName: String,
        devfileContent: String
    ): Boolean {
        return try {
            dw.applyLocalDevfile(namespace, workspaceName, devfileContent)
        } catch (t: Throwable) {
            thisLogger().warn("Could not apply local devfile to DevWorkspace.", t)
            false
        }
    }

    /**
     * Retrieves a devfile from the current action event context by inspecting the file
     * currently open in the editor or selected in the project tree. A file is considered
     * a devfile if its content matches the devfile schema (presence of `schemaVersion`
     * and `metadata`), so any filename is supported (e.g. `devfile.yaml`, `.devfile.yaml`,
     * or custom names like `my-workspace.yaml`).
     *
     * Checks multiple sources in order:
     * 1. The PSI file currently being edited (for editor context menu)
     * 2. The file associated with the current editor document
     * 3. The selected file(s) in project view (VIRTUAL_FILE or VIRTUAL_FILE_ARRAY)
     * 4. Selected items from PlatformDataKeys (for project view)
     *
     * @param e The [AnActionEvent] providing access to the current editor and project selection data.
     * @return The [VirtualFile] representing the devfile if found, otherwise `null`.
     */
    private fun getDevfile(e: AnActionEvent): VirtualFile? {
        getFromPsiFile(e)?.let { if (DevfileUtils.isDevfile(it)) return it }
        getFromEditor(e)?.let { if (DevfileUtils.isDevfile(it)) return it }
        getFromVirtualFile(e).firstOrNull { DevfileUtils.isDevfile(it) }?.let { return it }
        return null
    }

    private fun getFromVirtualFile(e: AnActionEvent): Sequence<VirtualFile> {
        val single = e.getData(CommonDataKeys.VIRTUAL_FILE)
        if (single != null && !single.isDirectory) {
            return sequenceOf(single)
        }
        val array = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY).orEmpty()
        val fromArray = array.asSequence().filter { !it.isDirectory }
        if (fromArray.any()) return fromArray
        val selectedItems = e.getData(PlatformDataKeys.SELECTED_ITEMS) ?: emptyArray<Any>()
        return selectedItems.asSequence().mapNotNull { item ->
            when (item) {
                is PsiFile -> item.virtualFile
                is VirtualFile -> item
                else -> null
            }
        }.filter { !it.isDirectory }.map { it }
    }

    private fun getFromPsiFile(e: AnActionEvent): VirtualFile? {
        val psiFile: PsiFile? = e.getData(CommonDataKeys.PSI_FILE)
        val vf = psiFile?.virtualFile
        return if (vf != null && !vf.isDirectory) vf else null
    }

    private fun getFromEditor(e: AnActionEvent): VirtualFile? {
        val editor: Editor? = e.getData(CommonDataKeys.EDITOR)
        return editor?.document?.let { doc ->
            FileDocumentManager.getInstance().getFile(doc)
        }?.takeIf { !it.isDirectory }
    }
}
