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

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

/**
 * Represents a devfile with methods to validate and read its content.
 */
class Devfile internal constructor(
    private val file: VirtualFile,
    private val documentProvider: (VirtualFile) -> Document? = {
        FileDocumentManager.getInstance().getCachedDocument(it)
    }
) {

    companion object {
        private const val DETECTION_LIMIT_BYTES = 8 * 1024

        private val SCHEMA_VERSION_PATTERN = Pattern.compile(
            "schemaVersion\\s*:\\s*[\"']?(\\d+\\.\\d+(?:\\.\\d+)?)"
        )

        /**
         * Creates a Devfile instance from a VirtualFile if the file is a valid devfile.
         * A file is considered a devfile if its content matches the devfile schema
         * (presence of `schemaVersion` >= 2 and `metadata`).
         *
         * Works with any filename (e.g. devfile.yaml, .devfile.yaml, my-workspace.yaml).
         * Only the first [DETECTION_LIMIT_BYTES] bytes are read for validation.
         *
         * @param file The virtual file to check and wrap.
         * @param documentProvider Lambda to provide cached documents (for testing).
         * @return A [Devfile] instance if the file is a valid devfile, otherwise `null`.
         */
        fun from(
            file: VirtualFile?,
            documentProvider: (VirtualFile) -> Document? = {
                FileDocumentManager.getInstance().getCachedDocument(it)
            }
        ): Devfile? {
            return if (file == null || file.isDirectory) {
                null
            }
            else if (isDevfile(file, documentProvider)) {
                Devfile(file, documentProvider)
            } else {
                null
            }
        }

        /**
         * Returns `true` if the file content looks like a devfile. Determined using:
         * * has "schemaVersion"
         * * schemaVersion >= 2
         * * has "metadata".
         *
         * Only the first [DETECTION_LIMIT_BYTES] bytes are read.
         * Works with any filename (e.g. devfile.yaml, .devfile.yaml, my-workspace.yaml).
         * Reads from the editor buffer if the file is open (includes unsaved changes),
         * otherwise reads from disk.
         *
         * @param file The virtual file to check (must be a regular file, not a directory).
         * @param documentProvider Lambda to provide cached documents.
         * @return true if the content matches devfile schema, false otherwise.
         */
        private fun isDevfile(
            file: VirtualFile,
            documentProvider: (VirtualFile) -> Document?
        ): Boolean {
            if (file.isDirectory) return false
            val content = try {
                val document = documentProvider(file)
                document?.text?.take(DETECTION_LIMIT_BYTES) // in editor
                    ?: file.inputStream.use {
                        it.readNBytes(DETECTION_LIMIT_BYTES).toString(StandardCharsets.UTF_8)
                    } // on disk
            } catch (_: Throwable) {
                return false
            }
            if (!content.contains("metadata")) return false
            val schemaMatcher = SCHEMA_VERSION_PATTERN.matcher(content)
            if (!schemaMatcher.find()) return false
            val major = schemaMatcher.group(1).substringBefore('.').toIntOrNull() ?: return false
            return major >= 2
        }
    }

    /**
     * Reads and returns the content of the devfile as a String.
     * Reads from the editor buffer if the file is open (includes unsaved changes),
     * otherwise reads from disk.
     *
     * @return The devfile content, or `null` if reading fails.
     */
    fun getContent(): String? {
        return try {
            val document = documentProvider(file)
            document?.text // file in editor
                ?: file.inputStream.use {
                    it.readBytes().toString(StandardCharsets.UTF_8)
                } // file on disk
        } catch (t: Throwable) {
            thisLogger().warn("Failed to read devfile: ${t.message}", t)
            null
        }
    }
}
