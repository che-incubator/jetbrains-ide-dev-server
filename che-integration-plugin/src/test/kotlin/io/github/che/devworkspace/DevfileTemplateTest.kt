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
    fun `#toTemplate drops devfile schemaVersion and metadata but keeps the template fields`() {
        // when
        val template = DevfileTemplate.parse(devfile()).toTemplate(null)

        // then
        assertThat(template).doesNotContainKeys("schemaVersion", "metadata")
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
    fun `#toTemplate honours the devfile when it empties the projects`() {
        // given a devfile that explicitly clears the project list
        val currentProjects = listOf(mapOf("name" to "project"))
        val content = devfile() + "\nprojects: []\n"

        // when
        val template = DevfileTemplate.parse(content).toTemplate(mapOf("projects" to currentProjects))

        // then the empty list is the user's deletion and is applied
        assertThat(template["projects"]).isEqualTo(emptyList<Any>())
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
    fun `#toTemplate adds Che-injected env to new and renamed containers`() {
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

        // then the workspace-scoped values are applied to the new container too
        assertThat(envNames(template, "new-container")).containsExactly("CHE_DASHBOARD_URL")
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

    @Test
    fun `#toTemplate keeps YAML 1-2 scalars as written`() {
        // given unquoted values that YAML 1.1 would coerce to booleans, numbers or dates
        val content = """
            schemaVersion: 2.2.0
            metadata:
              name: my-workspace
            components:
              - name: tooling
                container:
                  image: new-image
                  env:
                    - name: FLAG
                      value: on
                    - name: CODE
                      value: 0123
                    - name: DATE
                      value: 2024-01-01
        """.trimIndent()

        // when
        val template = DevfileTemplate.parse(content).toTemplate(null)

        // then
        val container = container(template, "tooling")
        assertThat(envValue(container, "FLAG")).isEqualTo("on")
        assertThat(envValue(container, "CODE")).isEqualTo("0123")
        assertThat(envValue(container, "DATE")).isEqualTo("2024-01-01")
    }

    @Test
    fun `#toTemplate keeps a bare dot as a string`() {
        // given a lone dot is a string in the YAML 1.2 core schema, not a float
        val content = """
            schemaVersion: 2.2.0
            metadata:
              name: my-workspace
            components:
              - name: tooling
                container:
                  image: new-image
                  env:
                    - name: DOT
                      value: .
        """.trimIndent()

        // when
        val template = DevfileTemplate.parse(content).toTemplate(null)

        // then
        assertThat(envValue(container(template, "tooling"), "DOT")).isEqualTo(".")
    }

    @Test
    fun `#toTemplate still resolves decimal floats`() {
        // given the decimal forms that YAML 1.2 does resolve as floats
        val content = """
            schemaVersion: 2.2.0
            metadata:
              name: my-workspace
            components:
              - name: tooling
                container:
                  image: new-image
                  env:
                    - name: LEADING
                      value: .5
                    - name: TRAILING
                      value: 5.
                    - name: BOTH
                      value: 5.5
                    - name: NEGATIVE
                      value: -1.5
                    - name: GROUPED
                      value: 1_000.5
                    - name: EXPONENT
                      value: 1.5e3
        """.trimIndent()

        // when
        val template = DevfileTemplate.parse(content).toTemplate(null)

        // then
        val tooling = container(template, "tooling")
        assertThat(envRawValue(tooling, "LEADING")).isEqualTo(0.5)
        assertThat(envRawValue(tooling, "TRAILING")).isEqualTo(5.0)
        assertThat(envRawValue(tooling, "BOTH")).isEqualTo(5.5)
        assertThat(envRawValue(tooling, "NEGATIVE")).isEqualTo(-1.5)
        assertThat(envRawValue(tooling, "GROUPED")).isEqualTo(1000.5)
        assertThat(envRawValue(tooling, "EXPONENT")).isEqualTo(1500.0)
    }

    @Test
    fun `#parse rejects duplicate keys`() {
        val content = """
            schemaVersion: 2.2.0
            metadata:
              name: my-workspace
            components:
              - name: tooling
                container:
                  image: new-image
            components:
              - name: other
                container:
                  image: other-image
        """.trimIndent()

        assertThatThrownBy { DevfileTemplate.parse(content) }
            .isInstanceOf(DevfileValidationException::class.java)
            .hasMessageContaining("not valid YAML")
    }

    @Test
    fun `#parse rejects a pre-v2 schema`() {
        assertThatThrownBy {
            DevfileTemplate.parse(
                """
                schemaVersion: 1.0.0
                metadata:
                  name: my-workspace
                components:
                  - name: tooling
                    container:
                      image: new-image
                """.trimIndent()
            )
        }
            .isInstanceOf(DevfileValidationException::class.java)
            .hasMessageContaining("schemaVersion 2")
    }

    @Test
    fun `#parse rejects a devfile missing schemaVersion`() {
        assertThatThrownBy {
            DevfileTemplate.parse(
                """
                metadata:
                  name: my-workspace
                components:
                  - name: tooling
                    container:
                      image: new-image
                """.trimIndent()
            )
        }
            .isInstanceOf(DevfileValidationException::class.java)
            .hasMessageContaining("schemaVersion")
            .hasMessageContaining("missing")
    }

    @Test
    fun `#parse rejects an unparseable schemaVersion`() {
        assertThatThrownBy {
            DevfileTemplate.parse(
                """
                schemaVersion: two
                metadata:
                  name: my-workspace
                components:
                  - name: tooling
                    container:
                      image: new-image
                """.trimIndent()
            )
        }
            .isInstanceOf(DevfileValidationException::class.java)
            .hasMessageContaining("Unsupported devfile schemaVersion: two")
    }

    @Test
    fun `#parse rejects a non-scalar schemaVersion without overflowing`() {
        // given a map whose value refers back to the map itself, an indirect cycle that
        // SnakeYAML preserves and whose toString() would overflow the stack
        val content = """
            schemaVersion: &a
              x: &b
                y: *a
            metadata:
              name: my-workspace
            components:
              - name: tooling
                container:
                  image: new-image
        """.trimIndent()

        // when / then a validation error is reported instead of a StackOverflowError
        assertThatThrownBy { DevfileTemplate.parse(content) }
            .isInstanceOf(DevfileValidationException::class.java)
            .hasMessageContaining("schemaVersion")
    }

    @Test
    fun `#toTemplate deep merges nested attributes`() {
        // given the devfile overrides one sub-key of a namespaced attribute
        val content = devfile().replace(
            "components:",
            "attributes:\n  controller.devfile.io/devworkspace-config:\n    cookie: from-devfile\ncomponents:"
        )

        // when
        val template = DevfileTemplate.parse(content).toTemplate(
            mapOf(
                "attributes" to mapOf(
                    "controller.devfile.io/devworkspace-config" to mapOf(
                        "name" to "my-config",
                        "namespace" to "my-namespace"
                    )
                )
            )
        )

        // then
        @Suppress("UNCHECKED_CAST")
        val attributes = template["attributes"] as Map<String, Any?>
        val config = attributes["controller.devfile.io/devworkspace-config"] as Map<String, Any?>
        assertThat(config)
            .containsEntry("name", "my-config")
            .containsEntry("namespace", "my-namespace")
            .containsEntry("cookie", "from-devfile")
    }

    @Test
    fun `#toTemplate carries over starterProjects`() {
        // given
        val starterProjects = listOf(mapOf("name" to "starter"))

        // when
        val template = DevfileTemplate.parse(devfile()).toTemplate(
            mapOf("starterProjects" to starterProjects)
        )

        // then
        assertThat(template["starterProjects"]).isEqualTo(starterProjects)
    }

    @Test
    fun `#toTemplate keeps devfile attributes when there is no current template`() {
        // given
        val content = devfile().replace(
            "components:",
            "attributes:\n  from-devfile: value\ncomponents:"
        )

        // when
        val template = DevfileTemplate.parse(content).toTemplate(null)

        // then
        assertThat(template["attributes"]).isEqualTo(mapOf("from-devfile" to "value"))
    }

    @Test
    fun `#toTemplate replaces a scalar or a map when the other side changes type`() {
        // given the devfile turns a scalar into a map and a map into a scalar
        val content = devfile().replace(
            "components:",
            """
            attributes:
              map-wins:
                nested: value
              scalar-wins: scalar
            components:
            """.trimIndent()
        )
        val currentAttributes = mapOf(
            "map-wins" to "was-a-scalar",
            "scalar-wins" to mapOf("nested" to "was-a-map")
        )

        // when
        val template = DevfileTemplate.parse(content)
            .toTemplate(mapOf("attributes" to currentAttributes))

        // then
        @Suppress("UNCHECKED_CAST")
        val attributes = template["attributes"] as Map<String, Any?>
        assertThat(attributes["map-wins"]).isEqualTo(mapOf("nested" to "value"))
        assertThat(attributes["scalar-wins"]).isEqualTo("scalar")
    }

    @Test
    fun `#toTemplate deep merges attributes several levels deep`() {
        // given both sides define the same three-level nested map
        val content = devfile().replace(
            "components:",
            """
            attributes:
              top:
                middle:
                  from-devfile: devfile
                  shared: devfile
            components:
            """.trimIndent()
        )
        val currentAttributes = mapOf(
            "top" to mapOf(
                "middle" to mapOf(
                    "from-workspace" to "workspace",
                    "shared" to "workspace"
                )
            )
        )

        // when
        val template = DevfileTemplate.parse(content)
            .toTemplate(mapOf("attributes" to currentAttributes))

        // then
        @Suppress("UNCHECKED_CAST")
        val middle = ((template["attributes"] as Map<String, Any?>)["top"] as Map<String, Any?>)["middle"] as Map<String, Any?>
        assertThat(middle)
            .containsEntry("from-workspace", "workspace")
            .containsEntry("from-devfile", "devfile")
            .containsEntry("shared", "devfile")
    }

    @Test
    fun `#toTemplate clears a nested attribute map when the devfile supplies it empty`() {
        // given the devfile explicitly empties one nested attribute map
        val content = devfile().replace(
            "components:",
            """
            attributes:
              some-key: {}
            components:
            """.trimIndent()
        )
        val currentAttributes = mapOf(
            "some-key" to mapOf("a" to 1, "b" to 2),
            "sibling-key" to mapOf("kept" to "value")
        )

        // when
        val template = DevfileTemplate.parse(content)
            .toTemplate(mapOf("attributes" to currentAttributes))

        // then the emptied map wins while the sibling attributes are still merged in
        @Suppress("UNCHECKED_CAST")
        val attributes = template["attributes"] as Map<String, Any?>
        assertThat(attributes["some-key"]).isEqualTo(emptyMap<String, Any?>())
        assertThat(attributes["sibling-key"]).isEqualTo(mapOf("kept" to "value"))
    }

    @Test
    fun `#toTemplate preserves attributes when the devfile supplies an empty top-level map`() {
        // given the devfile writes an empty top-level attributes map
        val content = devfile().replace(
            "components:",
            "attributes: {}\ncomponents:"
        )

        // when
        val template = DevfileTemplate.parse(content).toTemplate(
            mapOf(
                "attributes" to mapOf(
                    "controller.devfile.io/storage-type" to "ephemeral"
                )
            )
        )

        // then the Che-injected controller attributes are not cleared
        @Suppress("UNCHECKED_CAST")
        val attributes = template["attributes"] as Map<String, Any?>
        assertThat(attributes)
            .containsEntry("controller.devfile.io/storage-type", "ephemeral")
    }

    @Test
    fun `#toTemplate replaces attribute lists instead of merging them`() {
        // given
        val content = devfile().replace(
            "components:",
            "attributes:\n  list:\n    - from-devfile\ncomponents:"
        )

        // when
        val template = DevfileTemplate.parse(content)
            .toTemplate(mapOf("attributes" to mapOf("list" to listOf("a", "b"))))

        // then
        @Suppress("UNCHECKED_CAST")
        val attributes = template["attributes"] as Map<String, Any?>
        assertThat(attributes["list"]).isEqualTo(listOf("from-devfile"))
    }

    @Test
    fun `#toTemplate drops non-plugin components removed from the devfile`() {
        // given the devfile replaces the container and the workspace still has a plugin
        val content = """
            schemaVersion: 2.2.0
            metadata:
              name: my-workspace
            components:
              - name: new-container
                container:
                  image: new-image
        """.trimIndent()
        val currentTemplate = mapOf(
            "components" to listOf(
                mapOf("name" to "tooling", "container" to mapOf("image" to "old-image")),
                mapOf(
                    "name" to "che-idea",
                    "plugin" to mapOf("kubernetes" to mapOf("name" to "che-idea-template"))
                )
            )
        )

        // when
        val template = DevfileTemplate.parse(content).toTemplate(currentTemplate)

        // then the removed container stays removed, the plugin is still injected
        val names = (template["components"] as List<*>)
            .filterIsInstance<Map<*, *>>()
            .map { it["name"] }
        assertThat(names).containsExactly("new-container", "che-idea")
    }

    @Test
    fun `#toTemplate ignores current plugin components without a name`() {
        // given
        val currentTemplate = mapOf(
            "components" to listOf(
                mapOf("name" to "tooling", "container" to mapOf("image" to "old-image")),
                mapOf("plugin" to mapOf("uri" to "https://example.com/unnamed.yaml"))
            )
        )

        // when
        val template = DevfileTemplate.parse(devfile()).toTemplate(currentTemplate)

        // then
        val names = (template["components"] as List<*>)
            .filterIsInstance<Map<*, *>>()
            .map { it["name"] }
        assertThat(names).containsExactly("tooling")
    }

    @Test
    fun `#toTemplate lets the devfile override a Che-injected env value`() {
        // given the devfile explicitly defines a name Che would otherwise inject
        val currentTemplate = mapOf(
            "components" to listOf(
                mapOf(
                    "name" to "tooling",
                    "container" to mapOf(
                        "env" to listOf(
                            mapOf("name" to "CHE_DASHBOARD_URL", "value" to "https://old")
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
                    - name: CHE_DASHBOARD_URL
                      value: https://new
        """.trimIndent()

        // when
        val template = DevfileTemplate.parse(content).toTemplate(currentTemplate)

        // then no duplicate is added and the devfile value wins
        val tooling = container(template, "tooling")
        assertThat(envNames(template, "tooling")).containsExactly("CHE_DASHBOARD_URL")
        assertThat(envValue(tooling, "CHE_DASHBOARD_URL")).isEqualTo("https://new")
    }

    @Test
    fun `#toTemplate does not re-add an endpoint absent from the devfile`() {
        // given the running template has an endpoint the devfile omits
        val currentTemplate = mapOf(
            "components" to listOf(
                mapOf(
                    "name" to "tooling",
                    "container" to mapOf(
                        "endpoints" to listOf(
                            mapOf("name" to "web", "targetPort" to 8080, "protocol" to "http"),
                            mapOf("name" to "debug", "targetPort" to 5005, "protocol" to "http")
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
                  endpoints:
                    - name: web
                      targetPort: 8080
        """.trimIndent()

        // when
        val template = DevfileTemplate.parse(content).toTemplate(currentTemplate)

        // then
        val tooling = container(template, "tooling")
        val endpointNames = (tooling["endpoints"] as List<*>)
            .filterIsInstance<Map<*, *>>()
            .map { it["name"] }
        assertThat(endpointNames).containsExactly("web")
    }

    @Test
    fun `#toTemplate lets the devfile override carried over variables and empty starterProjects`() {
        // given
        val content = devfile().replace(
            "components:",
            """
            variables:
              USER: from-devfile
            starterProjects: []
            components:
            """.trimIndent()
        )

        // when
        val template = DevfileTemplate.parse(content).toTemplate(
            mapOf(
                "variables" to mapOf("USER" to "workspace"),
                "starterProjects" to listOf(mapOf("name" to "starter"))
            )
        )

        // then
        assertThat(template["variables"]).isEqualTo(mapOf("USER" to "from-devfile"))
        assertThat(template["starterProjects"]).isEqualTo(emptyList<Any>())
    }

    @Test
    fun `#parse rejects a devfile larger than 256 KiB`() {
        // given
        val content = "components: []\n" + "#".repeat(256 * 1024)

        // when / then
        assertThatThrownBy { DevfileTemplate.parse(content) }
            .isInstanceOf(DevfileValidationException::class.java)
            .hasMessageContaining("too large")
    }

    @Test
    fun `#toTemplate rejects a devfile with a self-referential YAML anchor`() {
        // given an anchor that refers to the map it defines, a cycle SnakeYAML preserves
        val content = """
            schemaVersion: 2.2.0
            metadata:
              name: my-workspace
            attributes: &loop
              self: *loop
            components:
              - name: tooling
                container:
                  image: new-image
        """.trimIndent()

        // when / then the copy reports a validation error instead of overflowing the stack
        assertThatThrownBy { DevfileTemplate.parse(content).toTemplate(null) }
            .isInstanceOf(DevfileValidationException::class.java)
            .hasMessageContaining("recursive")
    }

    @Test
    fun `#toTemplate copies a map and a list shared by two sibling keys`() {
        // given anchors referenced twice without a cycle, which is a legal devfile
        val content = """
            schemaVersion: 2.2.0
            metadata:
              name: my-workspace
            attributes:
              first: &shared
                a: 1
              second: *shared
              firstList: &sharedList
                - one
                - two
              secondList: *sharedList
            components:
              - name: tooling
                container:
                  image: new-image
        """.trimIndent()

        // when
        val template = DevfileTemplate.parse(content).toTemplate(null)

        // then both references are kept as independent, equally populated copies
        @Suppress("UNCHECKED_CAST")
        val attributes = template["attributes"] as Map<String, Any?>
        assertThat(attributes["first"]).isEqualTo(mapOf("a" to 1))
        assertThat(attributes["second"]).isEqualTo(mapOf("a" to 1))
        assertThat(attributes["first"]).isNotSameAs(attributes["second"])
        assertThat(attributes["firstList"]).isEqualTo(listOf("one", "two"))
        assertThat(attributes["secondList"]).isEqualTo(listOf("one", "two"))
        assertThat(attributes["firstList"]).isNotSameAs(attributes["secondList"])
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
        return envRawValue(container, name) as? String
    }

    private fun envRawValue(container: Map<*, *>, name: String): Any? {
        val env = container["env"] as? List<*> ?: return null
        return env.filterIsInstance<Map<*, *>>()
            .firstOrNull { it["name"] == name }
            ?.get("value")
    }
}
