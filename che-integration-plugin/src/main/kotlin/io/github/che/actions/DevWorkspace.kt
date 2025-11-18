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

data class DevWorkspace(
    private val metadata: DevWorkspaceObjectMeta,
    private val spec: DevWorkspaceSpec,
    private val status: DevWorkspaceStatus
) {

    companion object;

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as DevWorkspace

        if (metadata.name != other.metadata.name) return false
        if (metadata.namespace != other.metadata.namespace) return false
        if (metadata.cheEditor != other.metadata.cheEditor) return false

        return true
    }

    override fun hashCode(): Int {
        var result = metadata.hashCode()
        result = 31 * result + spec.hashCode()
        result = 31 * result + status.hashCode()
        return result
    }
}

data class DevWorkspaceObjectMeta(
    val name: String,
    val namespace: String,
    val uid: String,
    val cheEditor: String?
) {
    companion object
}

data class DevWorkspaceSpec(
    val started: Boolean
) {
    companion object
}

data class DevWorkspaceStatus(
    val phase: String
) {
    companion object

}
