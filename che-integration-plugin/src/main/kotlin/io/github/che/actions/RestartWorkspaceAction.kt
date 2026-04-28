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
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder
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
class RestartWorkspaceAction : AnAction(), DumbAware {

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
        val project = e.project
        val presentation = e.presentation

        if (project == null) {
            thisLogger().info("RestartWorkspaceAction: Not visible - no project")
            presentation.isEnabledAndVisible = false
            return
        }

        thisLogger().info("RestartWorkspaceAction: Checking for devfile in context. Place=${e.place}")
        val devfile = findDevfile(e)
        val isVisible = devfile != null

        if (!isVisible) {
            thisLogger().info("RestartWorkspaceAction: Not visible - no devfile found." +
                    "\nPSI_FILE=${e.getData(CommonDataKeys.PSI_FILE)?.name}" +
                    "\nVIRTUAL_FILE=${e.getData(CommonDataKeys.VIRTUAL_FILE)?.name}" +
                    "\nEDITOR=${e.getData(CommonDataKeys.EDITOR) != null}")
        } else {
            thisLogger().info("RestartWorkspaceAction: Visible - devfile found: ${devfile.filename}")
        }

        presentation.isEnabledAndVisible = isVisible
    }

    /**
     * Restart the DevWorkspace.
     *
     * Steps:
     * 1. Identify the project and the devfile (from editor or selection, by content).
     * 2. Read the content of the identified devfile.
     * 3. Obtain the current workspace name, namespace, and Kubernetes API client.
     * 4. Asynchronously apply the local devfile and restart annotation; JetBrains Gateway deletes the pod when it observes the annotation.
     * 5. Provide user feedback in dialog in case of failure.
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
            thisLogger().warn("Could not determine workspace name/namespace or connect to the cluster." +
                    "workspaceName: $workspaceName, namespace: $namespace, apiClient: $apiClient")
            Messages.showErrorDialog(project,
                "Could not determine workspace name/namespace or connect to the cluster.",
                "Restart Workspace")
            return
        }

        val restart = MessageDialogBuilder
            .yesNo(
                "Restart Workspace",
                "Are you sure you want to restart the workspace? This will apply the local devfile."
            )
            .yesText("Restart Workspace")
            .noText("Cancel")
            .ask(e.getData(PlatformDataKeys.CONTEXT_COMPONENT))

        if (!restart) {
            return
        }

        restart(apiClient, namespace, workspaceName, devfileContent, project)
    }

    private fun restart(
        apiClient: ApiClient,
        namespace: String,
        workspaceName: String,
        devfileContent: String,
        project: Project
    ) {
        ApplicationManager.getApplication().executeOnPooledThread {
            runBlocking {
                val patch = DevWorkspacePatch(apiClient)

                val modified = patch.applyDevfile(namespace, workspaceName, devfileContent)
                if (!modified) {
                    withContext(Dispatchers.Main) {
                        Messages.showErrorDialog(
                            project,
                            "Failed to apply local devfile to DevWorkspace.",
                            "Restart Workspace"
                        )
                    }
                    return@runBlocking
                }

                val applied = patch.applyRestart(namespace, workspaceName)
                if (!applied) {
                    // Rollback: remove the local devfile annotation to maintain atomicity
                    thisLogger().info("Rolling back devfile annotation for $namespace/$workspaceName due to restart annotation failure")
                    val rollbackSuccess = patch.removeDevfile(namespace, workspaceName)
                    if (rollbackSuccess) {
                        thisLogger().info("Successfully rolled back devfile annotation for $namespace/$workspaceName")
                    } else {
                        thisLogger().error("Failed to rollback devfile annotation for $namespace/$workspaceName - workspace may be in inconsistent state")
                    }

                    withContext(Dispatchers.Main) {
                        thisLogger().warn("Failed to annotate DevWorkspace $namespace/$workspaceName for restart. Aborting.")
                        Messages.showErrorDialog(
                            project,
                            "Could not restart workspace $namespace/$workspaceName. Could not annotate for restart",
                            "Restart Workspace"
                        )
                    }
                    return@runBlocking
                }

                withContext(Dispatchers.Main) {
                    Messages.showInfoMessage(
                        project,
                        "Workspace restart initiated successfully. The workspace will restart with the updated devfile.",
                        "Restart Workspace"
                    )
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
        thisLogger().debug("findDevfile: Checking PSI_FILE...")
        Devfile.from(getFromPsiFile(e))?.let {
            thisLogger().debug("findDevfile: Found devfile from PSI_FILE")
            return it
        }

        thisLogger().debug("findDevfile: Checking EDITOR...")
        Devfile.from(getFromEditor(e))?.let {
            thisLogger().debug("findDevfile: Found devfile from EDITOR")
            return it
        }

        thisLogger().debug("findDevfile: Checking VIRTUAL_FILE...")
        getFromVirtualFile(e).firstNotNullOfOrNull { Devfile.from(it) }?.let {
            thisLogger().debug("findDevfile: Found devfile from VIRTUAL_FILE")
            return it
        }

        thisLogger().info("findDevfile: No devfile found in any source")
        return null
    }

    private fun getFromVirtualFile(e: AnActionEvent): Sequence<VirtualFile> {
        val virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
        if (virtualFile != null && !virtualFile.isDirectory) {
            thisLogger().debug("getFromVirtualFile: Found single VIRTUAL_FILE: ${virtualFile.name}")
            return sequenceOf(virtualFile)
        }

        val array = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY).orEmpty()
        val fromArray = array.asSequence().filter { !it.isDirectory }
        if (fromArray.any()) {
            thisLogger().debug("getFromVirtualFile: Found files from VIRTUAL_FILE_ARRAY: ${fromArray.joinToString { it.name }}")
            return fromArray
        }

        val selectedItems = e.getData(PlatformDataKeys.SELECTED_ITEMS) ?: emptyArray<Any>()
        thisLogger().debug("getFromVirtualFile: Checking SELECTED_ITEMS, count=${selectedItems.size}")
        return selectedItems.asSequence().mapNotNull { item ->
            when (item) {
                is PsiFile -> item.virtualFile.also {
                    thisLogger().info("getFromVirtualFile: SELECTED_ITEMS contains PsiFile: ${it?.name}")
                }
                is VirtualFile -> item.also {
                    thisLogger().debug("getFromVirtualFile: SELECTED_ITEMS contains VirtualFile: ${it.name}")
                }
                else -> {
                    thisLogger().debug("getFromVirtualFile: SELECTED_ITEMS contains unknown type: ${item.javaClass.simpleName}")
                    null
                }
            }
        }
            .filter { !it.isDirectory }
            .map { it }
    }

    private fun getFromPsiFile(e: AnActionEvent): VirtualFile? {
        val psiFile: PsiFile? = e.getData(CommonDataKeys.PSI_FILE)
        val vf = psiFile?.virtualFile
        if (vf != null && !vf.isDirectory) {
            thisLogger().info("getFromPsiFile: Found file: ${vf.name}")
            return vf
        } else if (vf != null) {
            thisLogger().debug("getFromPsiFile: PSI_FILE is directory: ${vf.name}")
        } else {
            thisLogger().debug("getFromPsiFile: No PSI_FILE or virtualFile is null")
        }
        return null
    }

    private fun getFromEditor(e: AnActionEvent): VirtualFile? {
        val editor: Editor? = e.getData(CommonDataKeys.EDITOR)
        if (editor == null) {
            thisLogger().debug("getFromEditor: No EDITOR in context")
            return null
        }

        val vf = editor.document.let { doc ->
            FileDocumentManager.getInstance().getFile(doc)
        }?.takeIf { !it.isDirectory }

        if (vf != null) {
            thisLogger().debug("getFromEditor: Found file: ${vf.name}")
        } else {
            thisLogger().debug("getFromEditor: EDITOR present but no valid file found")
        }
        return vf
    }
}
