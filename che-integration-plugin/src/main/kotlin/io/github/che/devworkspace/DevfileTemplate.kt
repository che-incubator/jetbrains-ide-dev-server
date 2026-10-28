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

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.error.YAMLException
import org.yaml.snakeyaml.nodes.Tag
import org.yaml.snakeyaml.representer.Representer
import org.yaml.snakeyaml.resolver.Resolver
import java.util.Collections
import java.util.IdentityHashMap
import java.util.regex.Pattern

/**
 * Converts an edited workspace devfile into the `spec.template` of its DevWorkspace.
 *
 * The DevWorkspace Operator only reads `spec.template`. It does not accept the Devfile-only
 * `schemaVersion` and `metadata` fields, so those are dropped.
 *
 * A field the devfile does not mention at all is carried over from the current template (projects,
 * `dependentProjects`, `variables`, `starterProjects`, attributes, Che-injected environment
 * variables and plugin components), so that restarting does not strip them. A field the devfile
 * *does* mention is authoritative, including an empty list or map: that is how a user expresses a
 * deletion.
 *
 * The top-level `attributes` field is the exception: it is always deep-merged with the current
 * template's attributes rather than replaced, so an empty top-level `attributes` map preserves the
 * workspace's attributes (including the Che-injected controller attributes). Nested maps
 * inside `attributes` are still authoritative: a non-empty nested map is merged recursively, while
 * an explicitly empty nested map clears that nested map.
 *
 * Values are parsed with YAML 1.2 core semantics rather than SnakeYAML's default YAML 1.1
 * resolver, so `on`, `yes`, `0123` and unquoted dates stay strings in the published template.
 *
 * @param devfile the parsed devfile content
 */
internal class DevfileTemplate private constructor(
    private val devfile: Map<String, Any?>
) {

    companion object {
        /** Fields of `spec.template` that are copied from the devfile when it defines them. */
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

        /** Fields carried over from the current template when the devfile does not mention them. */
        private val CARRIED_OVER_IF_ABSENT: List<String> = listOf(
            "projects",
            "dependentProjects",
            "variables",
            "starterProjects"
        )

        /** The devfile is parsed inside the IDE; refuse content that cannot fit an annotation. */
        private const val MAX_DEVFILE_BYTES = 256 * 1024

        /**
         * Environment variables that Che injects into the workspace containers. They are not part
         * of the user's devfile and would be lost when the template is replaced.
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
         * @throws DevfileValidationException if the content is too large, not valid YAML, not a
         *         mapping, is missing or has an unparseable `schemaVersion`, uses a pre-v2 schema,
         *         or defines no container component
         */
        fun parse(content: String): DevfileTemplate {
            if (content.toByteArray(Charsets.UTF_8).size > MAX_DEVFILE_BYTES) {
                throw DevfileValidationException(
                    "The devfile is too large to apply: it may not exceed $MAX_DEVFILE_BYTES bytes."
                )
            }
            val loaded = loadYaml(content)
            val devfile = loaded as? Map<*, *>
                ?: throw DevfileValidationException("Devfile is not a YAML mapping.")
            val typedDevfile = devfile.entries.associate { (key, value) ->
                val name = key as? String
                    ?: throw DevfileValidationException(
                        "Devfile keys must be strings, found: ${key ?: "null"}."
                    )
                name to value
            }
            rejectPreV2Schema(typedDevfile["schemaVersion"])
            if (!hasContainerComponent(typedDevfile["components"])
                && typedDevfile["parent"] == null) {
                throw DevfileValidationException("Devfile defines no container component.")
            }
            return DevfileTemplate(typedDevfile)
        }

        private fun rejectPreV2Schema(schemaVersion: Any?) {
            if (schemaVersion == null) {
                throw DevfileValidationException(
                    "Devfile is missing schemaVersion; only devfile schemaVersion 2 and later " +
                        "are supported."
                )
            }
            if (schemaVersion !is String && schemaVersion !is Number) {
                // Never stringify a non-scalar: a map or list can be (indirectly) self-referential,
                // and its toString() would overflow the stack instead of failing validation.
                throw DevfileValidationException(
                    "Unsupported devfile schemaVersion. Only schemaVersion 2 and later are " +
                        "supported."
                )
            }
            val major = schemaVersion.toString().substringBefore('.').toIntOrNull()
                ?: throw DevfileValidationException(
                    "Unsupported devfile schemaVersion: $schemaVersion. Only schemaVersion 2 " +
                        "and later are supported."
                )
            if (major < 2) {
                throw DevfileValidationException(
                    "Only devfile schemaVersion 2 and later are supported (found: $schemaVersion)."
                )
            }
        }

        private fun loadYaml(content: String): Any? {
            val options = LoaderOptions().apply {
                isAllowDuplicateKeys = false
            }
            return try {
                Yaml(
                    SafeConstructor(options),
                    Representer(DumperOptions()),
                    DumperOptions(),
                    options,
                    DevfileResolver()
                ).load<Any?>(content)
            } catch (e: YAMLException) {
                throw DevfileValidationException("Devfile is not valid YAML: ${e.message}", e)
            } catch (e: RuntimeException) {
                throw DevfileValidationException("Devfile is not valid YAML: ${e.message}", e)
            }
        }

        private fun hasContainerComponent(components: Any?): Boolean {
            return (components as? List<*>)
                ?.any { (it as? Map<*, *>)?.get("container") is Map<*, *> }
                ?: false
        }
    }

    /**
     * Resolves YAML 1.2 core scalars (true/false, ints, floats, null) and nothing else as a
     * non-string type: no `yes|no|on|off`, no timestamps.
     *
     * The patterns are declared here rather than next to the other constants so that they cannot
     * be confused with the identically named YAML 1.1 patterns that [Resolver] exposes.
     */
    private class DevfileResolver : Resolver() {

        override fun addImplicitResolvers() {
            addImplicitResolver(Tag.BOOL, CORE_BOOL, "tTfF")
            addImplicitResolver(Tag.INT, CORE_INT, "-+0123456789")
            addImplicitResolver(Tag.FLOAT, CORE_FLOAT, "-+0123456789.")
            addImplicitResolver(Tag.MERGE, Resolver.MERGE, "<")
            addImplicitResolver(Tag.NULL, Resolver.NULL, "~nN\u0000")
            addImplicitResolver(Tag.NULL, Resolver.EMPTY, null)
        }

        private companion object {
            private val CORE_BOOL = Pattern.compile("^(?:true|True|TRUE|false|False|FALSE)$")
            private val CORE_INT = Pattern.compile(
                "^(?:[-+]?(?:0|[1-9][0-9_]*)|0x[0-9a-fA-F_]+|0o[0-7_]+)$"
            )
            private val CORE_FLOAT = Pattern.compile(
                // A decimal needs at least one digit on one side of the dot: a bare `.` is a
                // string in the YAML 1.2 core schema, not a float.
                "^(?:[-+]?(?:[0-9][0-9_]*\\.[0-9_]*|\\.[0-9_]+)(?:[eE][-+]?[0-9]+)?" +
                    "|[-+]?[0-9][0-9_]*(?:[eE][-+]?[0-9]+)" +
                    "|[-+]?\\.(?:inf|Inf|INF)|\\.(?:nan|NaN|NAN))$"
            )
        }
    }

    /**
     * Builds the new `spec.template`, carrying over the DevWorkspace data that the devfile does
     * not mention.
     *
     * @param currentTemplate the template currently set on the DevWorkspace, if any
     * @return the template to patch the DevWorkspace with
     */
    fun toTemplate(currentTemplate: Map<String, Any?>?): Map<String, Any?> {
        val template = LinkedHashMap<String, Any?>()
        TEMPLATE_FIELDS.forEach { field ->
            devfile[field]?.let { template[field] = deepCopy(it) }
        }
        carryOverIfAbsent(template, currentTemplate)
        mergeAttributes(template, currentTemplate)
        carryOverPluginComponents(template, currentTemplate)
        carryOverInjectedEnv(template["components"], currentTemplate?.get("components"))
        return template
    }

    /**
     * Keeps the fields the workspace was created with when the devfile does not mention them.
     * A field the devfile mentions, even when empty, is authoritative: that is how a deletion is
     * expressed.
     */
    private fun carryOverIfAbsent(
        template: MutableMap<String, Any?>,
        currentTemplate: Map<String, Any?>?
    ) {
        if (currentTemplate == null) {
            return
        }
        CARRIED_OVER_IF_ABSENT.forEach { field ->
            if (!template.containsKey(field) && currentTemplate.containsKey(field)) {
                template[field] = deepCopy(currentTemplate[field])
            }
        }
    }

    /**
     * Merges DevWorkspace attributes with the devfile's. The devfile wins at the leaves, but a
     * nested map the devfile only partially redefines keeps the workspace's sibling keys.
     *
     * A non-empty nested map is merged recursively. An explicitly empty nested map replaces, and
     * so clears, the existing nested map. The top-level map is always merged, never replaced: an
     * empty top-level `attributes` map is ignored, so that the Che-injected controller attributes
     * survive a user writing `attributes: {}`.
     */
    private fun mergeAttributes(
        template: MutableMap<String, Any?>,
        currentTemplate: Map<String, Any?>?
    ) {
        val current = currentTemplate?.get("attributes") as? Map<*, *>
        val edited = template["attributes"] as? Map<*, *>
        if (current == null && edited == null) {
            return
        }
        val merged = deepMerge(current ?: emptyMap<Any?, Any?>(), edited ?: emptyMap<Any?, Any?>())
        if (merged.isNotEmpty()) {
            template["attributes"] = merged
        }
    }

    /**
     * Recursively merges [override] into [base]. A non-empty map on both sides is merged
     * recursively; an explicitly empty map on the overriding side replaces the existing value, so
     * that it clears the existing nested map.
     */
    private fun deepMerge(base: Map<*, *>, override: Map<*, *>): MutableMap<String, Any?> {
        val merged = LinkedHashMap<String, Any?>()
        base.forEach { (key, value) -> merged[key.toString()] = deepCopy(value) }
        override.forEach { (key, value) ->
            val name = key.toString()
            val existing = merged[name]
            merged[name] = if (existing is Map<*, *> && value is Map<*, *> && value.isNotEmpty()) {
                deepMerge(existing, value)
            } else {
                deepCopy(value)
            }
        }
        return merged
    }

    /**
     * Keeps plugin components that the workspace was created with but that the devfile does not
     * define. This covers editors and default plugins that Che injects into `spec.template` (in
     * addition to the modern `spec.contributions` mechanism).
     */
    private fun carryOverPluginComponents(
        template: MutableMap<String, Any?>,
        currentTemplate: Map<String, Any?>?
    ) {
        val currentComponents = currentTemplate?.get("components") as? List<*> ?: return
        val pluginComponents = currentComponents
            .filterIsInstance<Map<*, *>>()
            .filter { it.containsKey("plugin") && it["name"] is String }
        if (pluginComponents.isEmpty()) {
            return
        }

        val newComponents = (template["components"] as? List<*>)?.toMutableList() ?: mutableListOf()
        val newComponentNames = newComponents
            .filterIsInstance<Map<*, *>>()
            .mapNotNull { it["name"] as? String }
            .toMutableSet()
        pluginComponents.forEach { component ->
            val name = component["name"] as String
            if (newComponentNames.add(name)) {
                newComponents.add(deepCopy(component))
            }
        }
        if (newComponents.isNotEmpty()) {
            template["components"] = newComponents
        }
    }

    /**
     * Re-adds Che-injected environment variables to every container of the new template.
     *
     * The values are workspace-scoped, so they are applied to any container that does not define
     * the name itself. Matching containers by name would lose them whenever a container is renamed
     * or added.
     */
    private fun carryOverInjectedEnv(components: Any?, currentComponents: Any?) {
        val newComponents = components as? List<*> ?: return
        val injected = injectedEnv(currentComponents)
        if (injected.isEmpty()) {
            return
        }
        newComponents.forEach { component ->
            val container = (component as? Map<*, *>)?.get("container") as? Map<*, *>
                ?: return@forEach
            addMissingInjectedEnv(container, injected)
        }
    }

    /** Collects the injected environment entries from every container of the current template. */
    @Suppress("UNCHECKED_CAST")
    private fun injectedEnv(currentComponents: Any?): Map<String, Map<*, *>> {
        val containers = (currentComponents as? List<*>)
            ?.mapNotNull { (it as? Map<*, *>)?.get("container") as? Map<*, *> }
            ?: return emptyMap()
        val injected = LinkedHashMap<String, Map<*, *>>()
        containers.forEach { container ->
            (container["env"] as? List<*>)
                ?.filterIsInstance<Map<*, *>>()
                ?.forEach { env ->
                    val name = env["name"] as? String ?: return@forEach
                    if (name in CHE_INJECTED_ENV_NAMES) {
                        injected.putIfAbsent(name, env)
                    }
                }
        }
        return injected
    }

    @Suppress("UNCHECKED_CAST")
    private fun addMissingInjectedEnv(
        container: Map<*, *>,
        injected: Map<String, Map<*, *>>
    ) {
        val mutableContainer = container as? MutableMap<Any?, Any?> ?: return
        val existingEnv = (container["env"] as? List<*>)?.toMutableList() ?: mutableListOf<Any?>()
        val existingNames = existingEnv
            .filterIsInstance<Map<*, *>>()
            .mapNotNull { it["name"] as? String }
            .toMutableSet()

        injected.forEach { (name, env) ->
            if (existingNames.add(name)) {
                existingEnv.add(deepCopy(env))
            }
        }
        if (existingEnv.isNotEmpty()) {
            mutableContainer["env"] = existingEnv
        }
    }

    /**
     * Deep-copies a parsed value into string-keyed mutable maps and mutable lists.
     *
     * A value that refers to itself, directly or through a nested map or list, is rejected with a
     * [DevfileValidationException]. YAML anchors can produce such a self-referential map, and
     * copying it would recurse until the stack overflows, which the restart path cannot catch.
     * Objects are tracked by identity only while they are on the active recursion path, so the
     * same object referenced twice as a sibling is still copied twice and remains allowed.
     */
    private fun deepCopy(value: Any?): Any? = deepCopy(value, newIdentitySet())

    private fun deepCopy(value: Any?, activePath: MutableSet<Any>): Any? {
        return when (value) {
            is Map<*, *> ->
                copyGuardingCycles(value, activePath) {
                    LinkedHashMap<String, Any?>().apply {
                        value.forEach { (key, nested) -> put(key.toString(), deepCopy(nested, activePath)) }
                    }
            }
            is List<*> ->
                copyGuardingCycles(value, activePath) {
                    value.mapTo(mutableListOf()) { deepCopy(it, activePath) }
                }
            else ->
                value
        }
    }

    /**
     * Runs [copy] for an object added to the active recursion path, removing it again afterwards so
     * that non-recursive repeated references are not mistaken for a cycle.
     */
    private inline fun <T> copyGuardingCycles(
        value: Any,
        activePath: MutableSet<Any>,
        copy: () -> T
    ): T {
        if (!activePath.add(value)) {
            throw DevfileValidationException(
                "The devfile contains a recursive reference and cannot be applied."
            )
        }
        return try {
            copy()
        } finally {
            activePath.remove(value)
        }
    }

    /** A set that compares its members by identity rather than by `equals`/`hashCode`. */
    private fun newIdentitySet(): MutableSet<Any> =
        Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
}

/**
 * Signals that a devfile cannot be applied to a DevWorkspace.
 *
 * @param message the reason shown to the user
 * @param cause the underlying failure, if any
 */
class DevfileValidationException(message: String, cause: Throwable? = null) :
    RestartException(Reason.INVALID_DEVFILE, message, cause)
