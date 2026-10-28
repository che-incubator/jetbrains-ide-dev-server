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
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.Messages
import io.github.che.devworkspace.DevWorkspacePatch
import io.github.che.devworkspace.DevWorkspaceUtils
import io.github.che.devworkspace.Devfile
import io.github.che.devworkspace.RestartException
import io.kubernetes.client.openapi.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.awt.Component

/**
 * An action that allows restarting a DevWorkspace by applying a local devfile.
 * The devfile is identified by content (schema: schemaVersion + metadata), so any filename
 * is supported. The action is enabled when a project is open and the file in the editor
 * or selected in the project tree is a devfile.
 */
class RestartWorkspaceAction : AnAction(), DumbAware {

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
        val devfile = project?.let { findDevfile(e) }
        val isVisible = devfile != null
        e.presentation.isEnabledAndVisible = isVisible

        val detail = when {
            project == null -> ", no project"
            devfile != null -> ", devfile=${devfile.filename}"
            else -> ", no devfile"
        }
        thisLogger().debug("RestartWorkspaceAction: place=${e.place}, visible=$isVisible$detail")
    }

    /**
     * Restart the DevWorkspace.
     *
     * Steps:
     * 1. Identify the project and the devfile (from editor or selection, by content).
     * 2. Read the content of the identified devfile.
     * 3. Obtain the current workspace name, namespace, and Kubernetes API client.
     * 4. Asynchronously read the workspace and compute the template the devfile produces.
     * 5. Ask the user to confirm, saying whether the devfile changes the workspace.
     * 6. Publish the pending template and restart annotations on the DevWorkspace to signal
     *    the DevSpaces gateway plugin to restart the workspace.
     * 7. Provide user feedback in dialog in case of failure.
     *
     * How the gateway acts on the annotations is owned by the `devspaces-gateway-plugin`
     * (see its `DevWorkspaceRestart`).
     *
     * @param e The [AnActionEvent] which contains information about the current context,
     *          such as the project and selected files.
     */
    override fun actionPerformed(e: AnActionEvent) {
        val project: Project = e.project ?: return

        val devfileContent = readDevfileContent(e) ?: return

        val workspaceName = DevWorkspaceUtils.getCurrentWorkspaceName()
        val namespace = DevWorkspaceUtils.getCurrentWorkspaceNamespace()
        val apiClient = DevWorkspaceUtils.createApiClient()

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

        // read on the EDT: the context is not meant to be queried from a background thread
        val contextComponent = e.getData(PlatformDataKeys.CONTEXT_COMPONENT)
        requestRestart(apiClient, namespace, workspaceName, devfileContent, project, contextComponent)
    }

    /**
     * Finds the devfile in the action context and reads its content, reporting failures to the user.
     *
     * @param event the action event carrying the editor or project-view selection
     * @return the devfile content, or `null` when no devfile was found or it could not be read
     */
    private fun readDevfileContent(event: AnActionEvent): String? {
        val devfile = findDevfile(event)
        if (devfile == null) {
            Messages.showErrorDialog(
                event.project,
                "Please select or open a devfile in the editor or project tree to restart the workspace.",
                "Restart Workspace"
            )
            return null
        }

        val content = devfile.getContent()
        if (content == null) {
            Messages.showErrorDialog(
                event.project,
                "Failed to read devfile. Please check the file and try again.",
                "Restart Workspace"
            )
            return null
        }

        return content
    }

    /**
     * @param event the action event carrying the editor or project-view selection
     * @return the first candidate whose content is a devfile, or `null` when there is none
     */
    private fun findDevfile(event: AnActionEvent): Devfile? {
        return ActionContext
            .files(event)
            .firstNotNullOfOrNull { Devfile.from(it) }
    }

    /**
     * Requests a restart of the workspace by publishing the pending template and the restart
     * annotations. The gateway plugin performs the actual restart.
     */
    private fun requestRestart(
        apiClient: ApiClient,
        namespace: String,
        workspaceName: String,
        devfileContent: String,
        project: Project,
        contextComponent: Component?
    ) {
        ApplicationManager.getApplication().executeOnPooledThread {
            runBlocking {
                try {
                    val patch = DevWorkspacePatch(apiClient)
                    val plan = patch.planRestart(namespace, workspaceName, devfileContent)
                    if (!confirmRestart(contextComponent, plan.hasChanges)) {
                        return@runBlocking
                    }

                    patch.publishRestart(namespace, workspaceName, plan.payload)
                    thisLogger().info("Published devfile and requested restart of $namespace/$workspaceName.")
                } catch (e: RestartException) {
                    thisLogger().warn(
                        "Restart of $namespace/$workspaceName failed (${e.reason}): ${e.message}"
                    )
                    showError(
                        project,
                        e.message ?: "Could not restart workspace $namespace/$workspaceName."
                    )
                }
            }
        }
    }

    private suspend fun confirmRestart(contextComponent: Component?, hasChanges: Boolean): Boolean {
        val message = if (hasChanges) {
            "Restart the workspace? Your devfile changes will be applied."
        } else {
            "No devfile changes. Restart the workspace anyway?"
        }

        return withContext(Dispatchers.Main) {
            MessageDialogBuilder
                .yesNo("Restart Workspace", message)
                .yesText("Restart Workspace")
                .noText("Cancel")
                .ask(contextComponent)
        }
    }

    private suspend fun showError(project: Project, message: String) {
        withContext(Dispatchers.Main) {
            Messages.showErrorDialog(project, message, "Restart Workspace")
        }
    }
}
