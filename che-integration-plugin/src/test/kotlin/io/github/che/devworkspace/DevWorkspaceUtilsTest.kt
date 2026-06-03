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

import io.kubernetes.client.openapi.ApiClient
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class DevWorkspaceUtilsTest {

    @Test
    fun `#getCurrentWorkspaceName returns workspace name when DEVWORKSPACE_NAME is set`() {
        // given
        val envProvider: (String) -> String? = { key -> if (key == "DEVWORKSPACE_NAME") "my-workspace" else null }

        // when
        val name = DevWorkspaceUtils.getCurrentWorkspaceName(envProvider)

        // then
        assertThat(name).isEqualTo("my-workspace")
    }

    @Test
    fun `#getCurrentWorkspaceName returns null when DEVWORKSPACE_NAME is not set`() {
        // given
        val envProvider: (String) -> String? = { null }

        // when
        val name = DevWorkspaceUtils.getCurrentWorkspaceName(envProvider)

        // then
        assertThat(name).isNull()
    }

    @Test
    fun `#getCurrentWorkspaceNamespace returns namespace when DEVWORKSPACE_NAMESPACE is set`() {
        // given
        val envProvider: (String) -> String? = { key -> if (key == "DEVWORKSPACE_NAMESPACE") "test-namespace" else null }

        // when
        val namespace = DevWorkspaceUtils.getCurrentWorkspaceNamespace(envProvider)

        // then
        assertThat(namespace).isEqualTo("test-namespace")
    }

    @Test
    fun `#getCurrentWorkspaceNamespace returns null when DEVWORKSPACE_NAMESPACE is not set`() {
        // given
        val envProvider: (String) -> String? = { null }

        // when
        val namespace = DevWorkspaceUtils.getCurrentWorkspaceNamespace(envProvider)

        // then
        assertThat(namespace).isNull()
    }

    @Test
    fun `#createApiClient uses KUBECONFIG env var when pointing to an existing file`(@TempDir tempDir: Path) {
        // given
        val kubeconfigFile = tempDir.resolve("kubeconfig").toFile()
        kubeconfigFile.writeText("apiVersion: v1\nkind: Config")

        val mockApiClient = mockk<ApiClient>()
        val envProvider: (String) -> String? = { key ->
            if (key == "KUBECONFIG") {
                kubeconfigFile.absolutePath
            } else {
                null
            }
        }
        val homeProvider: () -> String = { "/nonexistent-home-xyz" }
        var clientFactoryCalledWith: String? = "NOT_CALLED"
        val clientFactory: (String?) -> ApiClient = { path ->
            clientFactoryCalledWith = path
            mockApiClient
        }

        // when
        val result = DevWorkspaceUtils.createApiClient(envProvider, homeProvider, clientFactory)

        // then
        assertThat(result).isEqualTo(mockApiClient)
        assertThat(clientFactoryCalledWith).isEqualTo(kubeconfigFile.absolutePath)
    }

    @Test
    fun `#createApiClient falls back to defaultClient when KUBECONFIG file does not exist`() {
        // given
        val mockApiClient = mockk<ApiClient>()
        val envProvider: (String) -> String? = { key ->
            if (key == "KUBECONFIG") {
                "/nonexistent-path-xyz"
            } else {
                null
            }
        }
        val homeProvider: () -> String = { "/nonexistent-home-xyz" }
        var clientFactoryCalledWith: String? = "NOT_CALLED"
        val clientFactory: (String?) -> ApiClient = { path ->
            clientFactoryCalledWith = path
            mockApiClient
        }

        // when
        val result = DevWorkspaceUtils.createApiClient(envProvider, homeProvider, clientFactory)

        // then
        assertThat(result).isEqualTo(mockApiClient)
        assertThat(clientFactoryCalledWith).isNull()  // null → defaultClient path
    }

    @Test
    fun `#createApiClient uses default kube config when KUBECONFIG is not set but default config exists`(@TempDir tempDir: Path) {
        // given
        val kubeDir = tempDir.resolve(".kube").toFile()
        kubeDir.mkdirs()
        val kubeconfigFile = kubeDir.resolve("config")
        kubeconfigFile.writeText("apiVersion: v1\nkind: Config")

        val mockApiClient = mockk<ApiClient>()
        val envProvider: (String) -> String? = { null }
        val homeProvider: () -> String = { tempDir.toString() }
        var clientFactoryCalledWith: String? = "NOT_CALLED"
        val clientFactory: (String?) -> ApiClient = { path ->
            clientFactoryCalledWith = path
            mockApiClient
        }

        // when
        val result = DevWorkspaceUtils.createApiClient(envProvider, homeProvider, clientFactory)

        // then
        assertThat(result).isEqualTo(mockApiClient)
        assertThat(clientFactoryCalledWith).isEqualTo(kubeconfigFile.absolutePath)
    }

    @Test
    fun `#createApiClient falls back to defaultClient when neither KUBECONFIG nor default config is available`() {
        // given
        val mockApiClient = mockk<ApiClient>()
        val envProvider: (String) -> String? = { null }
        val homeProvider: () -> String = { "/nonexistent-home-xyz" }
        var clientFactoryCalledWith: String? = "NOT_CALLED"
        val clientFactory: (String?) -> ApiClient = { path ->
            clientFactoryCalledWith = path
            mockApiClient
        }

        // when
        val result = DevWorkspaceUtils.createApiClient(envProvider, homeProvider, clientFactory)

        // then
        assertThat(result).isEqualTo(mockApiClient)
        assertThat(clientFactoryCalledWith).isNull()  // null → defaultClient path
    }

    @Test
    fun `#createApiClient returns null when exception is thrown`() {
        // given
        val envProvider: (String) -> String? = { null }
        val homeProvider: () -> String = { "/nonexistent-home-xyz" }
        val clientFactory: (String?) -> ApiClient = { _ -> throw RuntimeException("Connection refused") }

        // when
        val result = DevWorkspaceUtils.createApiClient(envProvider, homeProvider, clientFactory)

        // then
        assertThat(result).isNull()
    }
}
