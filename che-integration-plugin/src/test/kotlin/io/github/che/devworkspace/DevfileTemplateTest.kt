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
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class DevfileTemplateTest {

    @Test
    fun `#parse rejects invalid YAML`() {
        assertThatThrownBy { DevfileTemplate.parse("components: [") }
            .isInstanceOf(DevfileValidationException::class.java)
            .hasMessageContaining("not valid YAML")
    }

    @Test
    fun `#parse rejects content that is not a mapping`() {
        assertThatThrownBy { DevfileTemplate.parse("- name: tooling") }
            .isInstanceOf(DevfileValidationException::class.java)
            .hasMessageContaining("not a YAML mapping")
    }

    @Test
    fun `#parse rejects a devfile without container component`() {
        assertThatThrownBy {
            DevfileTemplate.parse(
                """
                schemaVersion: 2.2.0
                metadata:
                  name: my-workspace
                """.trimIndent()
            )
        }
            .isInstanceOf(DevfileValidationException::class.java)
            .hasMessageContaining("no container component")
    }

    @Test
    fun `#toTemplate drops devfile schemaVersion and metadata`() {
        // when
        val template = DevfileTemplate.parse(devfile()).toTemplate(null)

        // then
        assertThat(template).doesNotContainKeys("schemaVersion", "metadata")
        assertThat(template).containsKey("components")
    }

    @Test
    fun `#toTemplate keeps components, commands and events`() {
        // when
        val template = DevfileTemplate.parse(devfile()).toTemplate(null)

        // then
        assertThat(template).containsKeys("components", "commands", "events")
    }

    @Test
    fun `#parse accepts a devfile that only declares a parent`() {
        // when
        val template = DevfileTemplate.parse(
            """
            schemaVersion: 2.2.0
            metadata:
              name: my-workspace
            parent:
              id: nodejs
            """.trimIndent()
        ).toTemplate(null)

        // then
        assertThat(template["parent"]).isEqualTo(mapOf("id" to "nodejs"))
    }

    @Test
    fun `#toTemplate keeps the parent reference`() {
        // given
        val content = devfile() + "\nparent:\n  id: nodejs\n"

        // when
        val template = DevfileTemplate.parse(content).toTemplate(null)

        // then
        assertThat(template["parent"]).isEqualTo(mapOf("id" to "nodejs"))
    }

    @Test
    fun `#toTemplate carries over projects when the devfile defines none`() {
        // given
        val currentProjects = listOf(mapOf("name" to "project", "git" to mapOf("remotes" to emptyMap<String, String>())))

        // when
        val template = DevfileTemplate.parse(devfile()).toTemplate(mapOf("projects" to currentProjects))

        // then
        assertThat(template["projects"]).isEqualTo(currentProjects)
    }

    @Test
    fun `#toTemplate carries over projects when the devfile projects are empty`() {
        // given
        val currentProjects = listOf(mapOf("name" to "project"))
        val content = devfile() + "\nprojects: []\n"

        // when
        val template = DevfileTemplate.parse(content).toTemplate(mapOf("projects" to currentProjects))

        // then
        assertThat(template["projects"]).isEqualTo(currentProjects)
    }

    @Test
    fun `#toTemplate prefers devfile projects`() {
        // given
        val content = devfile().replace(
            "components:",
            "projects:\n  - name: from-devfile\ncomponents:"
        )

        // when
        val template = DevfileTemplate.parse(content)
            .toTemplate(mapOf("projects" to listOf(mapOf("name" to "from-workspace"))))

        // then
        assertThat(template["projects"]).isEqualTo(listOf(mapOf("name" to "from-devfile")))
    }

    @Test
    fun `#toTemplate carries over attributes and lets the devfile override them`() {
        // given
        val content = devfile().replace(
            "components:",
            "attributes:\n  from-devfile: 'yes'\n  shared: devfile\ncomponents:"
        )

        // when
        val template = DevfileTemplate.parse(content).toTemplate(
            mapOf("attributes" to mapOf("from-workspace" to "yes", "shared" to "workspace"))
        )

        // then
        @Suppress("UNCHECKED_CAST")
        val attributes = template["attributes"] as Map<String, Any?>
        assertThat(attributes)
            .containsEntry("from-workspace", "yes")
            .containsEntry("from-devfile", "yes")
            .containsEntry("shared", "devfile")
    }

    @Test
    fun `#toTemplate carries over Che-injected env vars to same-named containers`() {
        // given
        val currentTemplate = mapOf(
            "components" to listOf(
                mapOf(
                    "name" to "tooling",
                    "container" to mapOf(
                        "image" to "current-image",
                        "env" to listOf(
                            mapOf("name" to "CHE_DASHBOARD_URL", "value" to "https://dashboard"),
                            mapOf("name" to "CLUSTER_CONSOLE_URL", "value" to "https://console")
                        )
                    )
                )
            )
        )

        // when
        val template = DevfileTemplate.parse(devfile()).toTemplate(currentTemplate)

        // then
        assertThat(envNames(template, "tooling"))
            .containsExactly("CHE_DASHBOARD_URL", "CLUSTER_CONSOLE_URL")
    }

    @Test
    fun `#toTemplate lets devfile env values win over carried over ones`() {
        // given
        val currentTemplate = mapOf(
            "components" to listOf(
                mapOf(
                    "name" to "tooling",
                    "container" to mapOf(
                        "env" to listOf(
                            mapOf("name" to "CHE_DASHBOARD_URL", "value" to "https://old"),
                            mapOf("name" to "USER_VAR", "value" to "old")
                        )
                    )
                )
            )
        )
        val content = """
            schemaVersion: 2.2.0
            metadata:
              name: my-workspace
            components:
              - name: tooling
                container:
                  image: new-image
                  env:
                    - name: USER_VAR
                      value: new
              - name: other
                container:
                  image: other-image
        """.trimIndent()

        // when
        val template = DevfileTemplate.parse(content).toTemplate(currentTemplate)

        // then
        val tooling = container(template, "tooling")
        assertThat(envNames(template, "tooling"))
            .containsExactlyInAnyOrder("CHE_DASHBOARD_URL", "USER_VAR")
        assertThat(envValue(tooling, "USER_VAR")).isEqualTo("new")
        assertThat(envValue(tooling, "CHE_DASHBOARD_URL")).isEqualTo("https://old")
    }

    @Test
    fun `#toTemplate adds no env to containers unknown to the current template`() {
        // given
        val currentTemplate = mapOf(
            "components" to listOf(
                mapOf(
                    "name" to "tooling",
                    "container" to mapOf(
                        "env" to listOf(mapOf("name" to "CHE_DASHBOARD_URL", "value" to "https://dashboard"))
                    )
                )
            )
        )
        val content = """
            schemaVersion: 2.2.0
            metadata:
              name: my-workspace
            components:
              - name: new-container
                container:
                  image: new-image
        """.trimIndent()

        // when
        val template = DevfileTemplate.parse(content).toTemplate(currentTemplate)

        // then
        assertThat(container(template, "new-container")["env"]).isNull()
    }

    @Test
    fun `#toTemplate carries over plugin components injected by Che`() {
        // given
        val currentTemplate = mapOf(
            "components" to listOf(
                mapOf(
                    "name" to "tooling",
                    "container" to mapOf("image" to "old-image")
                ),
                mapOf(
                    "name" to "che-idea",
                    "plugin" to mapOf("kubernetes" to mapOf("name" to "che-idea-template"))
                )
            )
        )

        // when
        val template = DevfileTemplate.parse(devfile()).toTemplate(currentTemplate)

        // then
        val components = (template["components"] as List<*>).filterIsInstance<Map<*, *>>()
        assertThat(components.map { it["name"] }).containsExactly("tooling", "che-idea")
    }

    @Test
    fun `#toTemplate does not duplicate plugin components defined by the devfile`() {
        // given
        val content = """
            schemaVersion: 2.2.0
            metadata:
              name: my-workspace
            components:
              - name: tooling
                container:
                  image: new-image
              - name: user-plugin
                plugin:
                  uri: https://example.com/plugin.yaml
        """.trimIndent()
        val currentTemplate = mapOf(
            "components" to listOf(
                mapOf("name" to "user-plugin", "plugin" to mapOf("uri" to "https://example.com/old.yaml"))
            )
        )

        // when
        val template = DevfileTemplate.parse(content).toTemplate(currentTemplate)

        // then
        val components = (template["components"] as List<*>).filterIsInstance<Map<*, *>>()
        assertThat(components.map { it["name"] }).containsExactly("tooling", "user-plugin")
        assertThat((components[1]["plugin"] as Map<*, *>)["uri"]).isEqualTo("https://example.com/plugin.yaml")
    }

    @Test
    fun `#toTemplate carries over template-only fields`() {
        // given
        val dependentProjects = listOf(mapOf("name" to "dependent"))
        val variables = mapOf("USER" to "developer")

        // when
        val template = DevfileTemplate.parse(devfile()).toTemplate(
            mapOf(
                "dependentProjects" to dependentProjects,
                "variables" to variables
            )
        )

        // then
        assertThat(template["dependentProjects"]).isEqualTo(dependentProjects)
        assertThat(template["variables"]).isEqualTo(variables)
    }

    private fun devfile(): String = """
        schemaVersion: 2.2.0
        metadata:
          name: my-workspace
        components:
          - name: tooling
            container:
              image: new-image
        commands:
          - id: build
            exec:
              component: tooling
              commandLine: mvn install
        events:
          postStart:
            - build
    """.trimIndent()

    private fun container(template: Map<String, Any?>, name: String): Map<*, *> {
        val components = template["components"] as List<*>
        val component = components
            .filterIsInstance<Map<*, *>>()
            .first { it["name"] == name }
        return component["container"] as Map<*, *>
    }

    private fun envNames(template: Map<String, Any?>, containerName: String): List<String> {
        val env = container(template, containerName)["env"] as? List<*> ?: return emptyList()
        return env.filterIsInstance<Map<*, *>>().mapNotNull { it["name"] as? String }
    }

    private fun envValue(container: Map<*, *>, name: String): String? {
        val env = container["env"] as? List<*> ?: return null
        return env.filterIsInstance<Map<*, *>>()
            .firstOrNull { it["name"] == name }
            ?.get("value") as? String
    }
}
