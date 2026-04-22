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
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import io.mockk.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

class RestartWorkspaceActionTest {

    private val action = RestartWorkspaceAction()

    private val validDevfileContent = """
        schemaVersion: 2.2.0
        metadata:
          name: my-workspace
        components:
          - name: tooling
    """.trimIndent()

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    // ===== update() =====

    @Test
    fun `#update disables action when project is null`() {
        // given
        val (event, presentation) = createMockEvent(project = null)

        // when
        action.update(event)

        // then
        verify { presentation.isEnabledAndVisible = false }
    }

    @Test
    fun `#update hides action when no devfile is in context`() {
        // given
        val (event, presentation) = createMockEvent()
        every { event.getData(CommonDataKeys.PSI_FILE) } returns null
        every { event.getData(CommonDataKeys.EDITOR) } returns null
        every { event.getData(CommonDataKeys.VIRTUAL_FILE) } returns null
        // relaxed mock returns Object (not null) for generic getData due to type erasure — explicit nulls required
        every { event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY) } returns null
        every { event.getData(PlatformDataKeys.SELECTED_ITEMS) } returns null

        // when
        action.update(event)

        // then
        verify { presentation.isEnabledAndVisible = false }
    }

    @Test
    fun `#update shows action when PSI_FILE is a valid devfile`() {
        // given
        val mockVirtualFile = createMockVirtualFile(validDevfileContent)
        val mockPsiFile = mockk<PsiFile>()
        every { mockPsiFile.virtualFile } returns mockVirtualFile

        val (event, presentation) = createMockEvent()
        every { event.getData(CommonDataKeys.PSI_FILE) } returns mockPsiFile
        createMockFileDocumentManager(mockVirtualFile)

        // when
        action.update(event)

        // then
        verify { presentation.isEnabledAndVisible = true }
    }

    @Test
    fun `#update shows action when VIRTUAL_FILE is a valid devfile`() {
        // given
        val mockVirtualFile = createMockVirtualFile(validDevfileContent)

        val (event, presentation) = createMockEvent()
        every { event.getData(CommonDataKeys.PSI_FILE) } returns null
        every { event.getData(CommonDataKeys.EDITOR) } returns null
        every { event.getData(CommonDataKeys.VIRTUAL_FILE) } returns mockVirtualFile
        createMockFileDocumentManager(mockVirtualFile)

        // when
        action.update(event)

        // then
        verify { presentation.isEnabledAndVisible = true }
    }

    @Test
    fun `#update hides action when VIRTUAL_FILE is not a devfile`() {
        // given
        val mockVirtualFile = createMockVirtualFile("just some yaml content without schemaVersion")

        val (event, presentation) = createMockEvent()
        every { event.getData(CommonDataKeys.PSI_FILE) } returns null
        every { event.getData(CommonDataKeys.EDITOR) } returns null
        every { event.getData(CommonDataKeys.VIRTUAL_FILE) } returns mockVirtualFile
        createMockFileDocumentManager(mockVirtualFile)

        // when
        action.update(event)

        // then
        verify { presentation.isEnabledAndVisible = false }
    }

    @Test
    fun `#update hides action when VIRTUAL_FILE is a directory`() {
        // given
        // relaxed = true so getName() is available for the debug log in update()
        val mockDirectory = mockk<VirtualFile>(relaxed = true)
        every { mockDirectory.isDirectory } returns true

        val (event, presentation) = createMockEvent()
        every { event.getData(CommonDataKeys.PSI_FILE) } returns null
        every { event.getData(CommonDataKeys.EDITOR) } returns null
        every { event.getData(CommonDataKeys.VIRTUAL_FILE) } returns mockDirectory
        // directory falls through to VIRTUAL_FILE_ARRAY and SELECTED_ITEMS; relaxed mock returns Object, not null
        every { event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY) } returns null
        every { event.getData(PlatformDataKeys.SELECTED_ITEMS) } returns null

        // when
        action.update(event)

        // then
        verify { presentation.isEnabledAndVisible = false }
    }

    /**
     * Creates a mock [AnActionEvent] paired with its [Presentation].
     * The event is relaxed so unspecified [AnActionEvent.getData] calls return null.
     * Note: getData calls returning array types must still be mocked explicitly — the relaxed mock
     * returns Object (not null) for generic methods due to type erasure, causing a ClassCastException.
     */
    private fun createMockEvent(project: Project? = mockk()): Pair<AnActionEvent, Presentation> {
        val presentation = mockk<Presentation>(relaxed = true)
        val event = mockk<AnActionEvent>(relaxed = true)
        every { event.project } returns project
        every { event.presentation } returns presentation
        return event to presentation
    }

    /**
     * Stubs [FileDocumentManager] so that [FileDocumentManager.getCachedDocument] returns null
     * for each given file, causing [io.github.che.devworkspace.Devfile] to fall back to reading
     * content from disk (i.e. from the VirtualFile's inputStream).
     */
    private fun createMockFileDocumentManager(vararg files: VirtualFile) {
        mockkStatic(FileDocumentManager::class)
        val mock = mockk<FileDocumentManager>()
        every { FileDocumentManager.getInstance() } returns mock
        files.forEach { every { mock.getCachedDocument(it) } returns null }
    }

    /**
     * Creates a mock [VirtualFile] backed by the given [content].
     * relaxed = true so getName() is available when it appears in debug log output.
     */
    private fun createMockVirtualFile(content: String): VirtualFile {
        return mockk(relaxed = true) {
            every { isDirectory } returns false
            every { inputStream } answers { ByteArrayInputStream(content.toByteArray()) }
        }
    }
}
