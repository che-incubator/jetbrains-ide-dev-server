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

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.error.YAMLException

/**
 * Converts an edited workspace devfile into the `spec.template` of its DevWorkspace.
 *
 * The DevWorkspace Operator only reads `spec.template`. It does not accept the Devfile-only
 * `schemaVersion` and `metadata` fields, so those are dropped. Fields that the DevWorkspace was
 * created with but that are absent from the edited devfile (projects, attributes, environment
 * variables injected by Che) are carried over so that applying the devfile does not strip them.
 *
 * @param devfile the parsed devfile content
 */
internal class DevfileTemplate private constructor(
    private val devfile: Map<String, Any?>
) {

    companion object {
        /** Fields of `spec.template` that exist in a Devfile. */
        private val TEMPLATE_FIELDS: List<String> = listOf(
            "attributes",
            "commands",
            "components",
            "dependentProjects",
            "events",
            "parent",
            "projects",
            "starterProjects",
            "variables"
        )

        /** Fields of `spec.template` that have no Devfile counterpart and are only carried over. */
        private val TEMPLATE_ONLY_FIELDS: List<String> = listOf(
            "dependentProjects",
            "variables"
        )

        /**
         * Environment variables that Che injects into every container when it creates the
         * DevWorkspace. They are not part of the user's devfile and would be lost when the
         * template is replaced.
         */
        private val CHE_INJECTED_ENV_NAMES: Set<String> = setOf(
            "CHE_DASHBOARD_URL",
            "CHE_PLUGIN_REGISTRY_URL",
            "CHE_PLUGIN_REGISTRY_INTERNAL_URL",
            "OPENVSX_REGISTRY_URL",
            "CLUSTER_CONSOLE_URL",
            "CLUSTER_CONSOLE_TITLE",
            "HOST_USERS"
        )

        /**
         * Parses and validates devfile content.
         *
         * @param content the devfile as YAML
         * @return the [DevfileTemplate] to build a `spec.template` from
         * @throws DevfileValidationException if the content is not valid YAML, is not a mapping,
         *         or defines no container component
         */
        fun parse(content: String): DevfileTemplate {
            val loaded = loadYaml(content)
            val devfile = loaded as? Map<*, *>
                ?: throw DevfileValidationException("Devfile is not a YAML mapping.")
            val typedDevfile = devfile.entries.associate { it.key.toString() to it.value }
            if (!hasContainerComponent(typedDevfile["components"])
                && typedDevfile["parent"] == null) {
                throw DevfileValidationException("Devfile defines no container component.")
            }
            return DevfileTemplate(typedDevfile)
        }

        private fun loadYaml(content: String): Any? {
            return try {
                Yaml(SafeConstructor(LoaderOptions())).load<Any?>(content)
            } catch (e: YAMLException) {
                throw DevfileValidationException("Devfile is not valid YAML.", e)
            }
        }

        private fun hasContainerComponent(components: Any?): Boolean {
            return (components as? List<*>)
                ?.any { (it as? Map<*, *>)?.get("container") is Map<*, *> }
                ?: false
        }
    }

    /**
     * Builds the new `spec.template`, carrying over the DevWorkspace data that the devfile does
     * not describe.
     *
     * @param currentTemplate the template currently set on the DevWorkspace, if any
     * @return the template to patch the DevWorkspace with
     */
    fun toTemplate(currentTemplate: Map<String, Any?>?): Map<String, Any?> {
        val template = LinkedHashMap<String, Any?>()
        TEMPLATE_FIELDS.forEach { field ->
            devfile[field]?.let { template[field] = it }
        }
        carryOverProjects(template, currentTemplate)
        carryOverTemplateOnlyFields(template, currentTemplate)
        mergeAttributes(template, currentTemplate)
        carryOverPluginComponents(template, currentTemplate)
        carryOverInjectedEnv(template["components"], currentTemplate?.get("components"))
        return template
    }

    /**
     * Keeps the projects the workspace was created with when the devfile does not define any.
     * The DevWorkspace Operator does not clone projects that are missing from the template.
     */
    private fun carryOverProjects(
        template: MutableMap<String, Any?>,
        currentTemplate: Map<String, Any?>?
    ) {
        if (isNullOrEmpty(template["projects"])) {
            currentTemplate?.get("projects")?.let { template["projects"] = it }
        }
    }

    /** Keeps DevWorkspace attributes the devfile does not redefine. */
    private fun mergeAttributes(
        template: MutableMap<String, Any?>,
        currentTemplate: Map<String, Any?>?
    ) {
        val merged = LinkedHashMap<String, Any?>()
        (currentTemplate?.get("attributes") as? Map<*, *>)?.forEach { merged[it.key.toString()] = it.value }
        (template["attributes"] as? Map<*, *>)?.forEach { merged[it.key.toString()] = it.value }
        if (merged.isNotEmpty()) {
            template["attributes"] = merged
        }
    }

    /**
     * Keeps template-only fields (they are not part of the Devfile schema) that the workspace was
     * created with, such as `dependentProjects` and `variables`.
     */
    private fun carryOverTemplateOnlyFields(
        template: MutableMap<String, Any?>,
        currentTemplate: Map<String, Any?>?
    ) {
        TEMPLATE_ONLY_FIELDS.forEach { field ->
            if (isNullOrEmpty(template[field])) {
                currentTemplate?.get(field)?.let { template[field] = it }
            }
        }
    }

    /**
     * Keeps plugin components that the workspace was created with but that the devfile does not
     * define. This covers editors and default plugins that Che injects into `spec.template` (in
     * addition to the modern `spec.contributions` mechanism), and that would otherwise
     * disappear from the workspace.
     */
    @Suppress("UNCHECKED_CAST")
    private fun carryOverPluginComponents(
        template: MutableMap<String, Any?>,
        currentTemplate: Map<String, Any?>?
    ) {
        val currentComponents = currentTemplate?.get("components") as? List<*>
            ?: return
        val pluginComponents = currentComponents
            .filterIsInstance<Map<*, *>>()
            .filter { it.containsKey("plugin") }
        if (pluginComponents.isEmpty()) {
            return
        }

        val newComponents = (template["components"] as? List<*>)?.toMutableList()
            ?: mutableListOf()
        val newComponentNames = newComponents
            .filterIsInstance<Map<*, *>>()
            .mapNotNull { it["name"] as? String }
            .toMutableSet()
        pluginComponents.forEach { component ->
            val name = component["name"] as? String
                ?: return@forEach
            if (newComponentNames.add(name)) {
                newComponents.add(component)
            }
        }
        if (newComponents.isNotEmpty()) {
            template["components"] = newComponents
        }
    }

    /**
     * Re-adds Che-injected environment variables to containers that exist under the same name in
     * the current template. Values defined in the devfile take precedence.
     */
    @Suppress("UNCHECKED_CAST")
    private fun carryOverInjectedEnv(components: Any?, currentComponents: Any?) {
        val newComponents = components as? MutableList<Any?>
            ?: return
        val currentContainers = (currentComponents as? List<*>)
            ?.filterIsInstance<Map<*, *>>()
            ?.mapNotNull { component ->
                val name = component["name"] as? String
                    ?: return@mapNotNull null
                val container = component["container"] as? Map<*, *>
                    ?: return@mapNotNull null
                name to container
            }
            ?.toMap()
            ?: return

        newComponents.forEach { component ->
            val newComponent = component as? MutableMap<Any?, Any?>
                ?: return@forEach
            val container = newComponent["container"] as? MutableMap<Any?, Any?>
                ?: return@forEach
            val currentContainer = currentContainers[newComponent["name"] as? String]
                ?: return@forEach
            addMissingInjectedEnv(container, currentContainer)
        }
    }

    private fun addMissingInjectedEnv(
        container: MutableMap<Any?, Any?>,
        currentContainer: Map<*, *>
    ) {
        val currentEnv = currentContainer["env"] as? List<*>
            ?: return
        val existingEnv = container["env"] as? List<*>
            ?: emptyList<Any?>()
        val newEnv = existingEnv.toMutableList()
        val newEnvNames = newEnv
            .filterIsInstance<Map<*, *>>()
            .mapNotNull { it["name"] as? String }
            .toMutableSet()

        currentEnv.filterIsInstance<Map<*, *>>().forEach { env ->
            val name = env["name"] as? String
                ?: return@forEach
            if (name in CHE_INJECTED_ENV_NAMES && newEnvNames.add(name)) {
                newEnv.add(env)
            }
        }
        if (newEnv.isNotEmpty()) {
            container["env"] = newEnv
        }
    }

    private fun isNullOrEmpty(value: Any?): Boolean {
        return value == null
                || (value is Collection<*>
                && value.isEmpty())
    }
}

/**
 * Signals that a devfile cannot be applied to a DevWorkspace.
 *
 * @param message the reason shown to the user
 * @param cause the underlying failure, if any
 */
class DevfileValidationException(message: String, cause: Throwable? = null) :
    RestartException(Reason.INVALID_DEVFILE, message, cause)
