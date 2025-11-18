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
package io.github.che.devworkspace

import com.intellij.openapi.editor.Document
import com.intellij.openapi.vfs.VirtualFile
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

class DevfileTest {

    // Sample valid devfile content
    private val validDevfileContent = """
        schemaVersion: 2.2.0
        metadata:
          name: my-workspace
        components:
          - name: tooling
            container:
              image: quay.io/devfile/universal-developer-image:latest
    """.trimIndent()

    // Valid devfile with schema version 2.0.0
    private val validDevfileV20 = """
        schemaVersion: 2.0.0
        metadata:
          name: workspace
    """.trimIndent()

    // Invalid devfile - schema version 1.0.0 (too old)
    private val invalidDevfileOldSchema = """
        schemaVersion: 1.0.0
        metadata:
          name: old-workspace
    """.trimIndent()

    // Invalid devfile - no metadata
    private val invalidDevfileNoMetadata = """
        schemaVersion: 2.2.0
        components:
          - name: tooling
    """.trimIndent()

    // Invalid devfile - no schemaVersion
    private val invalidDevfileNoSchema = """
        metadata:
          name: workspace
        components:
          - name: tooling
    """.trimIndent()

    @Test
    fun `#from returns null when file is null`() {
        // given
        val file: VirtualFile? = null

        // when
        val devfile = Devfile.from(file)

        // then
        assertThat(devfile).isNull()
    }

    @Test
    fun `#from returns null when file is directory`() {
        // given
        val mockFile = mockk<VirtualFile> {
            every { isDirectory } returns true
        }

        // when
        val devfile = Devfile.from(mockFile)

        // then
        assertThat(devfile).isNull()
    }

    @Test
    fun `#from returns Devfile when valid content from editor`() {
        // given
        val mockFile = createMockFile(isDirectory = false)
        val mockDocument = createMockDocument(validDevfileContent)
        val documentProvider: (VirtualFile) -> Document? = { mockDocument }

        // when
        val devfile = Devfile.from(mockFile, documentProvider)

        // then
        assertThat(devfile).isNotNull()
    }

    @Test
    fun `#from returns Devfile when valid content from disk`() {
        // given
        val mockFile = createMockFileWithInputStream(validDevfileContent, isDirectory = false)
        val documentProvider: (VirtualFile) -> Document? = { null }

        // when
        val devfile = Devfile.from(mockFile, documentProvider)

        // then
        assertThat(devfile).isNotNull()
    }

    @Test
    fun `#from returns null when schema version less than 2`() {
        // given
        val mockFile = createMockFileWithInputStream(invalidDevfileOldSchema, isDirectory = false)
        val documentProvider: (VirtualFile) -> Document? = { null }

        // when
        val devfile = Devfile.from(mockFile, documentProvider)

        // then
        assertThat(devfile).isNull()
    }

    @Test
    fun `#from returns null when metadata is missing`() {
        // given
        val mockFile = createMockFileWithInputStream(invalidDevfileNoMetadata, isDirectory = false)
        val documentProvider: (VirtualFile) -> Document? = { null }

        // when
        val devfile = Devfile.from(mockFile, documentProvider)

        // then
        assertThat(devfile).isNull()
    }

    @Test
    fun `#from returns null when schemaVersion is missing`() {
        // given
        val mockFile = createMockFileWithInputStream(invalidDevfileNoSchema, isDirectory = false)
        val documentProvider: (VirtualFile) -> Document? = { null }

        // when
        val devfile = Devfile.from(mockFile, documentProvider)

        // then
        assertThat(devfile).isNull()
    }

    @Test
    fun `#from returns Devfile when schema version is 2_0_0`() {
        // given
        val mockFile = createMockFileWithInputStream(validDevfileV20, isDirectory = false)
        val documentProvider: (VirtualFile) -> Document? = { null }

        // when
        val devfile = Devfile.from(mockFile, documentProvider)

        // then
        assertThat(devfile).isNotNull()
    }

    @Test
    fun `#from returns null when exception is thrown`() {
        // given
        val mockFile = mockk<VirtualFile> {
            every { isDirectory } returns false
            every { inputStream } throws RuntimeException("Test exception")
        }
        val documentProvider: (VirtualFile) -> Document? = { throw RuntimeException("Document error") }

        // when
        val devfile = Devfile.from(mockFile, documentProvider)

        // then
        assertThat(devfile).isNull()
    }

    @Test
    fun `#getContent returns content when reading from editor`() {
        // given
        val mockFile = createMockFile(isDirectory = false)
        val mockDocument = createMockDocument(validDevfileContent)
        val documentProvider: (VirtualFile) -> Document? = { mockDocument }
        val devfile = Devfile.from(mockFile, documentProvider)

        // when
        val content = devfile?.getContent()

        // then
        assertThat(content).isEqualTo(validDevfileContent)
    }

    @Test
    fun `#getContent returns content when reading from disk`() {
        // given
        val mockFile = createMockFileWithInputStream(validDevfileContent, isDirectory = false)
        val documentProvider: (VirtualFile) -> Document? = { null }
        val devfile = Devfile.from(mockFile, documentProvider)

        // when
        val content = devfile?.getContent()

        // then
        assertThat(content).isEqualTo(validDevfileContent)
    }

    @Test
    fun `#getContent returns null when reading fails`() {
        // given
        val mockFile = mockk<VirtualFile> {
            every { isDirectory } returns false
            every { inputStream } returns ByteArrayInputStream(validDevfileContent.toByteArray()) andThenThrows RuntimeException("Read error")
        }
        val documentProvider: (VirtualFile) -> Document? = { null }
        val devfile = Devfile.from(mockFile, documentProvider)

        // when
        val content = devfile?.getContent()

        // then
        assertThat(content).isNull()
    }

    @Test
    fun `#from returns Devfile when schema version has various formats`() {
        // given
        val variants = listOf(
            "schemaVersion: 2.2.0",
            "schemaVersion: \"2.2.0\"",
            "schemaVersion: '2.2.0'",
            "schemaVersion:2.2.0",
            "schemaVersion  :  2.2.0"
        )

        // when/then
        variants.forEach { schemaLine ->
            val content = """
                $schemaLine
                metadata:
                  name: test
            """.trimIndent()

            val mockFile = createMockFileWithInputStream(content, isDirectory = false)
            val documentProvider: (VirtualFile) -> Document? = { null }

            val devfile = Devfile.from(mockFile, documentProvider)

            assertThat(devfile)
                .withFailMessage("Failed for: $schemaLine")
                .isNotNull()
        }
    }

    @Test
    fun `#from returns null when schema is beyond 8KB limit`() {
        // given
        val largeContent = "a".repeat(8 * 1024) + "\nschemaVersion: 2.2.0\nmetadata:\n  name: test"
        val mockFile = createMockFileWithInputStream(largeContent, isDirectory = false)
        val documentProvider: (VirtualFile) -> Document? = { null }

        // when
        val devfile = Devfile.from(mockFile, documentProvider)

        // then
        assertThat(devfile).isNull()
    }

    // Helper: Create a mock VirtualFile
    private fun createMockFile(isDirectory: Boolean): VirtualFile {
        return mockk {
            every { this@mockk.isDirectory } returns isDirectory
        }
    }

    // Helper: Create a mock Document with specific text content
    private fun createMockDocument(text: String): Document {
        return mockk {
            every { this@mockk.text } returns text
        }
    }

    // Helper: Create a mock VirtualFile with an InputStream
    private fun createMockFileWithInputStream(content: String, isDirectory: Boolean): VirtualFile {
        return mockk {
            every { this@mockk.isDirectory } returns isDirectory
            // Mock inputStream to return a fresh ByteArrayInputStream each time it's called
            every { inputStream } answers { ByteArrayInputStream(content.toByteArray()) }
        }
    }
}
