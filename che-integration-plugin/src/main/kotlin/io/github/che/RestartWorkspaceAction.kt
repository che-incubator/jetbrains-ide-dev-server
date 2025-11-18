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
import com.redhat.devtools.gateway.openshift.DevWorkspaces
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class RestartWorkspaceAction : AnAction(
    "Restart Workspace from Local Devfile",
    "Restart the workspace using the local devfile.yaml",
    null
) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        // You must know the workspaceId — adjust how you fetch it
        val workspaceId = DevWorkspaces.getCurrentWorkspaceId(project)
        if (workspaceId == null) {
            Messages.showErrorDialog(
                project,
                "Could not find the active workspace ID.",
                "Restart Workspace"
            )
            return
        }

        // Run restart in background
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = DevWorkspaces.restartWorkspace(workspaceId, project)

                if (!result) {
                    Messages.showErrorDialog(
                        project,
                        "Workspace restart failed.",
                        "Restart Workspace"
                    )
                } else {
                    Messages.showInfoMessage(
                        project,
                        "Workspace restart initiated successfully.",
                        "Restart Workspace"
                    )
                }
            } catch (ex: Exception) {
                Messages.showErrorDialog(
                    project,
                    "Exception during restart: ${ex.message}",
                    "Restart Workspace"
                )
            }
        }
    }
}

