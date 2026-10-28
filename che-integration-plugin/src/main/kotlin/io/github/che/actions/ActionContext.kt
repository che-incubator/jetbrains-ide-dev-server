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

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile

/**
 * Resolves the files of an action context.
 *
 * Files are collected in this order so that callers can pick the most relevant one first:
 * 1. the PSI file currently being edited (editor context menu)
 * 2. the file backing the current editor document
 * 3. the file(s) selected in the project view (`VIRTUAL_FILE`, `VIRTUAL_FILE_ARRAY`,
 *    `SELECTED_ITEMS`)
 *
 * The result is materialised once and de-duplicated: the edited file is usually also the selected
 * one, and a caller that validates files would otherwise read it several times.
 */
internal object ActionContext {

    fun files(event: AnActionEvent): List<VirtualFile> {
        val files = buildList {
            fromPsiFile(event)?.let(::add)
            fromEditor(event)?.let(::add)
            addAll(selectedFiles(event))
        }
        return files.distinct()
    }

    private fun fromPsiFile(event: AnActionEvent): VirtualFile? {
        return event.getData(CommonDataKeys.PSI_FILE)
            ?.virtualFile
            ?.takeIf { !it.isDirectory }
    }

    private fun fromEditor(event: AnActionEvent): VirtualFile? {
        val document = event.getData(CommonDataKeys.EDITOR)?.document ?: return null
        return FileDocumentManager.getInstance()
            .getFile(document)
            ?.takeIf { !it.isDirectory }
    }

    private fun selectedFiles(event: AnActionEvent): List<VirtualFile> {
        event.getData(CommonDataKeys.VIRTUAL_FILE)
            ?.takeIf { !it.isDirectory }
            ?.let { return listOf(it) }

        event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
            ?.filter { !it.isDirectory }
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }

        return event.getData(PlatformDataKeys.SELECTED_ITEMS)
            .orEmpty()
            .mapNotNull { it.toVirtualFile() }
            .filter { !it.isDirectory }
    }

    private fun Any.toVirtualFile(): VirtualFile? {
        return when (this) {
            is PsiFile -> virtualFile
            is VirtualFile -> this
            else -> null
        }
    }
}
