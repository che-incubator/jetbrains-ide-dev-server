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

/**
 * Compares a `spec.template` built from a devfile with the one the DevWorkspace currently has.
 *
 * The two sides come from different parsers: the devfile is read with SnakeYAML, the workspace
 * with Jackson. Map key order and numeric types therefore differ without any semantic difference,
 * so a plain `==` would report a change where there is none.
 */
internal object TemplateEquality {

    /**
     * @param left a value from the template built from the devfile
     * @param right a value from the template read from the DevWorkspace
     * @return `true` when both values are semantically equal
     */
    fun isEqual(left: Any?, right: Any?): Boolean {
        return when {
            left == null || right == null -> left == null && right == null
            left is Map<*, *> && right is Map<*, *> -> isEqualMaps(left, right)
            left is List<*> && right is List<*> -> isEqualLists(left, right)
            left is Number && right is Number -> isEqualNumbers(left, right)
            else -> left == right
        }
    }

    private fun isEqualMaps(left: Map<*, *>, right: Map<*, *>): Boolean {
        if (left.size != right.size) {
            return false
        }
        val rightByKey = right.entries.associate { it.key.toString() to it.value }
        return left.entries.all { (key, value) ->
            rightByKey.containsKey(key.toString()) && isEqual(value, rightByKey[key.toString()])
        }
    }

    private fun isEqualLists(left: List<*>, right: List<*>): Boolean {
        if (left.size != right.size) {
            return false
        }
        return left.indices.all { isEqual(left[it], right[it]) }
    }

    private fun isEqualNumbers(left: Number, right: Number): Boolean {
        return left.toString().toBigDecimal().compareTo(right.toString().toBigDecimal()) == 0
    }
}
