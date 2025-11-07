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
package io.github.che

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import io.github.che.userActivity.UserActivityService

internal class StartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.getService<UserActivityService>(UserActivityService::class.java)
    }
}