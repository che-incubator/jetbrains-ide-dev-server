/*
 * Copyright (c) 2026 Red Hat, Inc.
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

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import io.github.che.devworkspace.DevWorkspacePatch
import io.github.che.devworkspace.DevWorkspaceUtils
import io.github.che.devworkspace.Devfile
import io.kubernetes.client.openapi.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

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

        val devfile = findDevfile(e)
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
     * 4. Asynchronously apply the local devfile and restart annotation; JetBrains Gateway deletes the pod when it observes the annotation.
     * 5. Provide user feedback through dialog messages for success or failure.
     *
     * @param e The [AnActionEvent] which contains information about the current context,
     *          such as the project and selected files.
     */
    override fun actionPerformed(e: AnActionEvent) {
        val project: Project = e.project ?: return

        val devfile = findDevfile(e)
        if (devfile == null) {
            Messages.showErrorDialog(project, "Please select or open a devfile in the editor or project tree to restart the workspace.", "Restart Workspace")
            return
        }

        val devfileContent = devfile.getContent()
        if (devfileContent == null) {
            Messages.showErrorDialog(
                project,
                "Failed to read devfile. Please check the file and try again.",
                "Restart Workspace"
            )
            return
        }

        val workspaceName = DevWorkspaceUtils.getCurrentWorkspaceName()
        val namespace = DevWorkspaceUtils.getCurrentWorkspaceNamespace()
        val apiClient: ApiClient? = DevWorkspaceUtils.createApiClient()

        if (workspaceName.isNullOrBlank()
            || namespace.isNullOrBlank()
            || apiClient == null) {
            Messages.showErrorDialog(project,
                "Could not determine workspace name/namespace or connect to the cluster.",
                "Restart Workspace")
            return
        }

        if (Messages.showYesNoDialog(
                project,
                "Are you sure you want to restart the workspace? This will apply the local devfile.",
                "Restart Workspace",
                Messages.getQuestionIcon()
            ) != Messages.YES) {
            return
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            runBlocking {
                val patch = DevWorkspacePatch(apiClient)

                val modified = patch.applyDevfile(namespace, workspaceName, devfileContent)
                if (!modified) {
                    withContext(Dispatchers.Main) {
                        Messages.showErrorDialog(project,
                            "Failed to apply local devfile to DevWorkspace.",
                            "Restart Workspace")
                    }
                    return@runBlocking
                }

                val applied = patch.applyRestart(namespace, workspaceName)
                if (!applied) {
                    withContext(Dispatchers.Main) {
                        thisLogger().warn("Failed to annotate DevWorkspace $namespace/$workspaceName for restart. Aborting.")
                        Messages.showErrorDialog(project,
                            "Could not restart workspace $namespace/$workspaceName. Could not annotate for restart",
                            "Restart Workspace")
                    }
                    return@runBlocking
                }

                withContext(Dispatchers.Main) {
                    Messages.showInfoMessage(project,
                        "Workspace restart initiated successfully. The workspace will restart with the updated devfile.",
                        "Restart Workspace")
                }
            }
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
     * @return The [io.github.che.devworkspace.Devfile] instance if found, otherwise `null`.
     */
    private fun findDevfile(e: AnActionEvent): Devfile? {
        Devfile.from(getFromPsiFile(e))?.let { return it }
        Devfile.from(getFromEditor(e))?.let { return it }
        getFromVirtualFile(e).firstNotNullOfOrNull { Devfile.from(it) }?.let { return it }
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
