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

import com.intellij.openapi.vfs.VirtualFile
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

/**
 * Utilities for detecting and working with devfile content.
 */
object DevfileUtils {

    private const val DETECTION_LIMIT_BYTES = 8 * 1024

    private val SCHEMA_VERSION_PATTERN = Pattern.compile(
        "schemaVersion\\s*:\\s*[\"']?(\\d+\\.\\d+(?:\\.\\d+)?)"
    )

    /**
     * Returns true if the file content looks like a devfile. Determined using the following:
     * * has "schemaVersion"
     * * schemaVersion >= 2
     * * "metadata".
     *
     * Only the first [DETECTION_LIMIT_BYTES] bytes are read.
     * Works with any filename (e.g. devfile.yaml, .devfile.yaml, my-workspace.yaml).
     *
     * For a more in-depth detection one could switch to the [com.intellij.psi.PsiManager] API
     * which then would add a dependency to the YAML plugin.
     *
     * @param file The virtual file to check (must be a regular file, not a directory).
     * @return true if the content matches devfile schema, false otherwise.
     */
    fun isDevfile(file: VirtualFile): Boolean {
        if (file.isDirectory) return false
        val content = try {
            file.inputStream.readNBytes(DETECTION_LIMIT_BYTES).toString(StandardCharsets.UTF_8)
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
