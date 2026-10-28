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
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class ActionContextTest {

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `returns the PSI file, the editor file and the selection in that order`() {
        // given
        val psi = virtualFile("from-psi.yaml")
        val editorFile = virtualFile("from-editor.yaml")
        mockFileDocumentManager(editorFile)

        // when
        val files = ActionContext.files(
            event(
                psiFile = psiFile(psi),
                editor = editor(),
                virtualFile = virtualFile("from-selection.yaml")
            )
        )

        // then
        assertThat(files.map { it.name })
            .containsExactly("from-psi.yaml", "from-editor.yaml", "from-selection.yaml")
    }

    @Test
    fun `returns the editor file when there is no PSI file`() {
        // given
        val editorFile = virtualFile("from-editor.yaml")
        mockFileDocumentManager(editorFile)

        // when
        val files = ActionContext.files(event(editor = editor()))

        // then
        assertThat(files).containsExactly(editorFile)
    }

    @Test
    fun `returns the selected virtual file`() {
        // given
        val file = virtualFile("selected.yaml")

        // when
        val files = ActionContext.files(event(virtualFile = file))

        // then
        assertThat(files).containsExactly(file)
    }

    @Test
    fun `returns the files of the selected file array, skipping directories`() {
        // given
        val directory = virtualFile("a-directory", isDirectory = true)
        val file = virtualFile("selected.yaml")
        val event = event(virtualFiles = arrayOf(directory, file))

        // when
        val files = ActionContext.files(event)

        // then
        assertThat(files).containsExactly(file)
    }

    @Test
    fun `returns the files of the selected items`() {
        // given
        val fromPsi = virtualFile("from-psi-item.yaml")
        val fromVirtualFile = virtualFile("from-virtual-file-item.yaml")
        val event = event(
            selectedItems = arrayOf(
                "not a file",
                psiFile(fromPsi),
                fromVirtualFile
            )
        )

        // when
        val files = ActionContext.files(event)

        // then
        assertThat(files).containsExactly(fromPsi, fromVirtualFile)
    }

    @Test
    fun `de-duplicates a file that is edited and selected`() {
        // given
        val file = virtualFile("devfile.yaml")
        mockFileDocumentManager(file)
        val event = event(
            psiFile = psiFile(file),
            editor = editor(),
            virtualFile = file
        )

        // when
        val files = ActionContext.files(event)

        // then
        assertThat(files).containsExactly(file)
    }

    @Test
    fun `unions the single selection with the array and the selected items`() {
        // given a project view where the lead selection is not the devfile
        val lead = virtualFile("README.md")
        val fromArray = virtualFile("devfile.yaml")
        val fromItems = virtualFile("other.yaml")
        val event = event(
            virtualFile = lead,
            virtualFiles = arrayOf(fromArray),
            selectedItems = arrayOf(fromItems)
        )

        // when
        val files = ActionContext.files(event)

        // then
        assertThat(files.map { it.name })
            .containsExactly("devfile.yaml", "README.md", "other.yaml")
    }

    @Test
    fun `ignores directories`() {
        // given
        val directory = virtualFile("a-directory", isDirectory = true)
        val event = event(
            virtualFile = directory,
            selectedItems = arrayOf(directory)
        )

        // when
        val files = ActionContext.files(event)

        // then
        assertThat(files).isEmpty()
    }

    @Test
    fun `returns no files when the context has no file`() {
        // when
        val files = ActionContext.files(event())

        // then
        assertThat(files).isEmpty()
    }

    private fun event(
        psiFile: PsiFile? = null,
        editor: Editor? = null,
        virtualFile: VirtualFile? = null,
        virtualFiles: Array<VirtualFile>? = null,
        selectedItems: Array<Any>? = null
    ): AnActionEvent {
        return mockk(relaxed = true) {
            every { getData(CommonDataKeys.PSI_FILE) } returns psiFile
            every { getData(CommonDataKeys.EDITOR) } returns editor
            every { getData(CommonDataKeys.VIRTUAL_FILE) } returns virtualFile
            every { getData(CommonDataKeys.VIRTUAL_FILE_ARRAY) } returns virtualFiles
            every { getData(PlatformDataKeys.SELECTED_ITEMS) } returns selectedItems
        }
    }

    private fun editor(): Editor {
        return mockk { every { document } returns mockk() }
    }

    private fun psiFile(file: VirtualFile?): PsiFile {
        return mockk { every { virtualFile } returns file }
    }

    private fun virtualFile(name: String, isDirectory: Boolean = false): VirtualFile {
        return mockk(relaxed = true) {
            every { this@mockk.name } returns name
            every { this@mockk.isDirectory } returns isDirectory
        }
    }

    private fun mockFileDocumentManager(file: VirtualFile) {
        mockkStatic(FileDocumentManager::class)
        val documentManager = mockk<FileDocumentManager>()
        every { FileDocumentManager.getInstance() } returns documentManager
        every { documentManager.getFile(any()) } returns file
    }
}
