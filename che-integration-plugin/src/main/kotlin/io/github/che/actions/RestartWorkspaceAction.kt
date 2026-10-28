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

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.awt.Component
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * An action that allows restarting a DevWorkspace by applying a local devfile.
 * The devfile is identified by content (schema: schemaVersion + metadata), so any filename
 * is supported. The action is enabled when a project is open and the file in the editor
 * or selected in the project tree is a devfile.
 */
class RestartWorkspaceAction : AnAction(), DumbAware {

    companion object {
        private const val TITLE = "Restart Workspace"
        private const val NOTIFICATION_GROUP = "io.github.che.restart"

        /**
         * Guards against a second restart while one is being planned or published. Two concurrent
         * publications would each remove and re-add the restart marker, producing two restart
         * requests for the gateway.
         */
        private val restartInFlight = AtomicBoolean(false)
    }

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }

    /**
     * Updates the state of the action. This method is called by the system to determine
     * whether the action should be visible and enabled in the current context.
     *
     * The action is enabled only if a project is open, a restart is not already running, and the
     * file in the editor or selected in the project view is a devfile (identified by content).
     *
     * @param e The [AnActionEvent] containing information about the current context.
     */
    override fun update(e: AnActionEvent) {
        val project = e.project
        val devfile = project?.let { findDevfile(e) }
        val isVisible = devfile != null && !restartInFlight.get()
        e.presentation.isEnabledAndVisible = isVisible

        val detail = when {
            project == null -> ", no project"
            restartInFlight.get() -> ", restart in progress"
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
     * 2. Obtain the current workspace name and namespace.
     * 3. Asynchronously read the devfile and connect to the cluster.
     * 4. Read the workspace and compute the template the devfile produces.
     * 5. Asks the user to confirm that the devfile should be applied.
     * 6. Publish the pending template and restart annotations on the DevWorkspace to signal
     *    the DevSpaces gateway plugin to restart the workspace.
     * 7. Report success non-modally, and failures in a dialog.
     *
     * How the gateway acts on the annotations is owned by the `devspaces-gateway-plugin`.
     *
     * @param e The [AnActionEvent] which contains information about the current context,
     *          such as the project and selected files.
     */
    override fun actionPerformed(e: AnActionEvent) {
        val project: Project = e.project ?: return
        if (!restartInFlight.compareAndSet(false, true)) {
            return
        }

        val devfile = findDevfile(e)
        val workspaceName = DevWorkspaceUtils.getCurrentWorkspaceName()
        val namespace = DevWorkspaceUtils.getCurrentWorkspaceNamespace()

        if (devfile == null) {
            restartInFlight.set(false)
            Messages.showErrorDialog(
                project,
                "Please select or open a devfile in the editor or project tree to restart the workspace.",
                TITLE
            )
            return
        }
        if (workspaceName.isNullOrBlank()
            || namespace.isNullOrBlank()) {
            restartInFlight.set(false)
            thisLogger().warn(
                "Could not determine workspace name/namespace." +
                        " workspaceName: $workspaceName, namespace: $namespace"
            )
            Messages.showErrorDialog(
                project,
                "Could not determine workspace name/namespace or connect to the cluster.",
                TITLE
            )
            return
        }

        // read on the EDT: the context is not meant to be queried from a background thread
        val contextComponent = e.getData(PlatformDataKeys.CONTEXT_COMPONENT)
        try {
            requestRestart(namespace, workspaceName, devfile, project, contextComponent)
        } catch (t: Throwable) {
            restartInFlight.set(false)
            throw t
        }
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
     *
     * Every failure is reported to the user: the devfile is read and the cluster connection is
     * created on the pooled thread, and any exception other than cancellation is turned into a
     * dialog.
     */
    private fun requestRestart(
        namespace: String,
        workspaceName: String,
        devfile: Devfile,
        project: Project,
        contextComponent: Component?
    ) {
        ApplicationManager.getApplication().executeOnPooledThread {
            runBlocking {
                try {
                    val devfileContent = readDevfileContent(devfile)
                        ?: throw RestartException(
                            RestartException.Reason.INVALID_DEVFILE,
                            "Failed to read devfile. Please check the file and try again."
                        )
                    val apiClient = DevWorkspaceUtils.createApiClient()
                        ?: throw RestartException(
                            RestartException.Reason.RESTART_PREPARATION_FAILED,
                            "Could not connect to the cluster."
                        )

                    val patch = DevWorkspacePatch(apiClient)
                    val plan = patch.planRestart(namespace, workspaceName, devfileContent)
                    if (!confirmRestart(contextComponent)) {
                        return@runBlocking
                    }

                    patch.publishRestart(
                        namespace,
                        workspaceName,
                        devfileContent,
                        plan.resourceVersion
                    )
                    thisLogger().info("Published devfile and requested restart of $namespace/$workspaceName.")
                    notifyRestartRequested(project)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: RestartException) {
                    thisLogger().warn(
                        "Restart of $namespace/$workspaceName failed (${e.reason}): ${e.message}"
                    )
                    showError(project, userMessage(e, workspaceName))
                } catch (e: Exception) {
                    thisLogger().error("Restart of $namespace/$workspaceName failed", e)
                    showError(
                        project,
                        "Could not restart workspace $namespace/$workspaceName: ${e.message}"
                    )
                } finally {
                    restartInFlight.set(false)
                }
            }
        }
    }

    /**
     * Reads the devfile content under a read action, on the pooled thread.
     */
    private fun readDevfileContent(devfile: Devfile): String? {
        return ApplicationManager.getApplication().runReadAction<String?> { devfile.getContent() }
    }

    /**
     * @return the message to show for [e], falling back per [RestartException.Reason]
     */
    private fun userMessage(e: RestartException, workspaceName: String): String {
        e.message?.let { return it }
        return when (e.reason) {
            RestartException.Reason.INVALID_DEVFILE -> "The devfile cannot be applied."
            RestartException.Reason.PAYLOAD_TOO_LARGE -> "The devfile is too large to apply."
            RestartException.Reason.RESTART_PREPARATION_FAILED ->
                "Could not prepare the restart of workspace $workspaceName."
            RestartException.Reason.PUBLISH_FAILED ->
                "Could not apply the devfile to workspace $workspaceName."
        }
    }

    private suspend fun confirmRestart(contextComponent: Component?): Boolean {
        return withContext(Dispatchers.Main) {
            MessageDialogBuilder
                .yesNo(TITLE, "Restarting the workspace applies the devfile")
                .yesText("Restart Workspace")
                .noText("Cancel")
                .ask(contextComponent)
        }
    }

    /**
     * A non-modal confirmation that the request was published. The workspace stops shortly after,
     * so a modal dialog would race the IDE closing.
     */
    private fun notifyRestartRequested(project: Project) {
        if (project.isDisposed) {
            return
        }
        ApplicationManager.getApplication().invokeLater {
            runCatching {
                NotificationGroupManager.getInstance()
                    .getNotificationGroup(NOTIFICATION_GROUP)
                    .createNotification(
                        TITLE,
                        "Restart requested. The workspace restarts when the gateway picks up the request.",
                        NotificationType.INFORMATION
                    )
                    .notify(project)
            }.onFailure { thisLogger().debug("Could not show the restart notification", it) }
        }
    }

    /**
     * Best-effort error dialog: a disposed project or a shutting-down EDT must not turn one
     * failure into an uncaught exception.
     */
    private suspend fun showError(project: Project, message: String) {
        runCatching {
            withContext(Dispatchers.Main) {
                if (!project.isDisposed) {
                    Messages.showErrorDialog(project, message, TITLE)
                }
            }
        }.onFailure { thisLogger().warn("Could not show the restart error", it) }
    }
}
