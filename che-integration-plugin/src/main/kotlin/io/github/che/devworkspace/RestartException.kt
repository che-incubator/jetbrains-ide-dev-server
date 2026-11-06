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
 * Signals that a workspace restart cannot be prepared or published.
 *
 * @param reason machine-readable category of the failure
 * @param message the reason shown to the user
 * @param cause the underlying failure, if any
 */
open class RestartException(
    val reason: Reason,
    message: String,
    cause: Throwable? = null
) : Exception(message, cause) {

    enum class Reason {
        /** The devfile content cannot be applied to a DevWorkspace. */
        INVALID_DEVFILE,

        /** The DevWorkspace could not be read or the restart payload could not be built. */
        RESTART_PREPARATION_FAILED,

        /** The DevWorkspace could not be patched with the template and restart annotation. */
        PUBLISH_FAILED
    }
}
