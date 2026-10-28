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

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TemplateEqualityTest {

    @Test
    fun `ignores the order of map keys`() {
        val fromDevfile = linkedMapOf("image" to "tooling", "memory" to "1Gi")
        val fromWorkspace = linkedMapOf("memory" to "1Gi", "image" to "tooling")

        assertThat(TemplateEquality.isEqual(fromDevfile, fromWorkspace)).isTrue()
    }

    @Test
    fun `treats numbers of different types as equal`() {
        assertThat(TemplateEquality.isEqual(1, 1L)).isTrue()
        assertThat(TemplateEquality.isEqual(1, 2L)).isFalse()
        assertThat(TemplateEquality.isEqual(1.0, 1)).isTrue()
    }

    @Test
    fun `compares nested maps and lists`() {
        val fromDevfile = mapOf(
            "components" to listOf(
                mapOf("name" to "tools", "container" to mapOf("image" to "tooling"))
            )
        )
        val fromWorkspace = mapOf(
            "components" to listOf(
                mapOf("container" to mapOf("image" to "tooling"), "name" to "tools")
            )
        )

        assertThat(TemplateEquality.isEqual(fromDevfile, fromWorkspace)).isTrue()
    }

    @Test
    fun `respects the order of list items`() {
        val fromDevfile = mapOf("components" to listOf("first", "second"))
        val fromWorkspace = mapOf("components" to listOf("second", "first"))

        assertThat(TemplateEquality.isEqual(fromDevfile, fromWorkspace)).isFalse()
    }

    @Test
    fun `reports different sizes as different`() {
        val fromDevfile = mapOf("components" to listOf("only"))
        val fromWorkspace = mapOf("components" to listOf("only", "extra"))

        assertThat(TemplateEquality.isEqual(fromDevfile, fromWorkspace)).isFalse()
    }

    @Test
    fun `reports a missing key as different`() {
        val fromDevfile = mapOf("components" to listOf("only"))
        val fromWorkspace = mapOf("components" to listOf("only"), "projects" to listOf("project"))

        assertThat(TemplateEquality.isEqual(fromDevfile, fromWorkspace)).isFalse()
    }

    @Test
    fun `compares null values`() {
        assertThat(TemplateEquality.isEqual(null, null)).isTrue()
        assertThat(TemplateEquality.isEqual(null, "value")).isFalse()
        assertThat(TemplateEquality.isEqual("value", null)).isFalse()
    }
}
