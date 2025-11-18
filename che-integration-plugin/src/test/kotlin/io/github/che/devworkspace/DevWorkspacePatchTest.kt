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

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.intellij.testFramework.LoggedErrorProcessor
import io.kubernetes.client.custom.V1Patch
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.models.V1Status
import io.kubernetes.client.util.generic.KubernetesApiResponse
import io.kubernetes.client.util.generic.dynamic.DynamicKubernetesApi
import io.kubernetes.client.util.generic.dynamic.DynamicKubernetesObject
import io.kubernetes.client.util.generic.options.PatchOptions
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DevWorkspacePatchTest {

    private lateinit var mockGenericApi: DynamicKubernetesApi
    private lateinit var mapper: ObjectMapper
    private lateinit var devWorkspacePatch: DevWorkspacePatch

    private val namespace = "test-namespace"
    private val workspaceName = "test-workspace"

    @BeforeEach
    fun setUp() {
        mockGenericApi = mockk(relaxed = true)
        mapper = ObjectMapper()
        devWorkspacePatch = DevWorkspacePatch(mockGenericApi, mapper)
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `#applyRestart should return true when patch succeeds`() {
        runBlocking {
            // given
            mockPatchReturns(createSuccessResponse())

            // when
            val result = devWorkspacePatch.applyRestart(namespace, workspaceName)

            // then
            assertThat(result).isTrue()

            verify {
                mockGenericApi.patch(
                    namespace, workspaceName, V1Patch.PATCH_FORMAT_JSON_PATCH, any<V1Patch>(), any<PatchOptions>()
                )
            }
        }
    }

    @Test
    fun `#applyRestart should return false when patch fails`() {
        LoggedErrorProcessor.executeWith<RuntimeException>(object : LoggedErrorProcessor() {
            override fun processError(category: String, message: String, details: Array<out String>, t: Throwable?): Set<Action> {
                return Action.NONE
            }
        }) {
            runBlocking {
                // given
                mockPatchReturns(createFailureResponse(403, "Forbidden"))

                // when
                val result = devWorkspacePatch.applyRestart(namespace, workspaceName)

                // then
                assertThat(result).isFalse()
            }
        }
    }

    @Test
    fun `#applyRestart should return false when exception is thrown`() {
        LoggedErrorProcessor.executeWith<RuntimeException>(object : LoggedErrorProcessor() {
            override fun processError(category: String, message: String, details: Array<out String>, t: Throwable?): Set<Action> {
                return Action.NONE
            }
        }) {
            runBlocking {
                // given
                mockPatchThrows(RuntimeException("Network error"))

                // when
                val result = devWorkspacePatch.applyRestart(namespace, workspaceName)

                // then
                assertThat(result).isFalse()
            }
        }
    }

    @Test
    fun `#applyRestart should create correct patch with restart annotation`() {
        runBlocking {
            // given
            val capturedPatch = slot<V1Patch>()
            mockPatchCaptures(capturedPatch, createSuccessResponse())

            // when
            devWorkspacePatch.applyRestart(namespace, workspaceName)

            // then
            val patchJson = capturedPatch.captured.value
            val patchOps = mapper.readTree(patchJson) as ArrayNode

            assertThat(patchOps).hasSize(1)
            val op = patchOps[0]
            assertThat(op.get("op").asText()).isEqualTo("add")
            assertThat(op.get("path").asText()).isEqualTo("/metadata/annotations/che.eclipse.org~1restart-in-progress")
            assertThat(op.get("value").asText()).isEqualTo("true")
        }
    }

    @Test
    fun `#applyDevfile should return true when patch succeeds`() {
        runBlocking {
            // given
            val devfileContent = """
            schemaVersion: 2.2.0
            metadata:
              name: my-workspace
        """.trimIndent()

            mockPatchReturns(createSuccessResponse())

            // when
            val result = devWorkspacePatch.applyDevfile(namespace, workspaceName, devfileContent)

            // then
            assertThat(result).isTrue()

            verify {
                mockGenericApi.patch(
                    namespace, workspaceName, V1Patch.PATCH_FORMAT_JSON_PATCH, any<V1Patch>(), any<PatchOptions>()
                )
            }
        }
    }

    @Test
    fun `#applyDevfile should return false when patch fails`() {
        LoggedErrorProcessor.executeWith<RuntimeException>(object : LoggedErrorProcessor() {
            override fun processError(category: String, message: String, details: Array<out String>, t: Throwable?): Set<Action> {
                return Action.NONE
            }
        }) {
            runBlocking {
                // given
                val devfileContent = "test-content"
                mockPatchReturns(createFailureResponse(404, "Not Found"))

                // when
                val result = devWorkspacePatch.applyDevfile(namespace, workspaceName, devfileContent)

                // then
                assertThat(result).isFalse()
            }
        }
    }

    @Test
    fun `#applyDevfile should return false when exception is thrown`() {
        LoggedErrorProcessor.executeWith<RuntimeException>(object : LoggedErrorProcessor() {
            override fun processError(category: String, message: String, details: Array<out String>, t: Throwable?): Set<Action> {
                return Action.NONE
            }
        }) {
            runBlocking {
                // given
                val devfileContent = "test-content"
                mockPatchThrows(ApiException("Connection timeout"))

                // when
                val result = devWorkspacePatch.applyDevfile(namespace, workspaceName, devfileContent)

                // then
                assertThat(result).isFalse()
            }
        }
    }

    @Test
    fun `#applyDevfile should create correct patch with devfile content`() {
        runBlocking {
            // given
            val devfileContent = """
            schemaVersion: 2.2.0
            metadata:
              name: my-workspace
            components:
              - name: tooling
        """.trimIndent()

            val capturedPatch = slot<V1Patch>()
            mockPatchCaptures(capturedPatch, createSuccessResponse())

            // when
            devWorkspacePatch.applyDevfile(namespace, workspaceName, devfileContent)

            // then
            val patchJson = capturedPatch.captured.value
            val patchOps = mapper.readTree(patchJson) as ArrayNode

            assertThat(patchOps).hasSize(1)
            val op = patchOps[0]
            assertThat(op.get("op").asText()).isEqualTo("add")
            assertThat(op.get("path").asText()).isEqualTo("/metadata/annotations/che.eclipse.org~1local-devfile")
            assertThat(op.get("value").asText()).isEqualTo(devfileContent)
        }
    }

    @Test
    fun `#applyDevfile should use JSON_PATCH format`() {
        runBlocking {
            // given
            val capturedFormat = slot<String>()
            val mockResponse = createSuccessResponse()

            every {
                mockGenericApi.patch(
                    any(), any(), capture(capturedFormat), any<V1Patch>(), any<PatchOptions>()
                )
            } returns mockResponse

            // when
            devWorkspacePatch.applyRestart(namespace, workspaceName)

            // then
            assertThat(capturedFormat.captured).isEqualTo(V1Patch.PATCH_FORMAT_JSON_PATCH)
        }
    }

    @Test
    fun `#applyRestart should correctly escape forward slashes in annotation key`() {
        runBlocking {
            // given
            val capturedPatch = slot<V1Patch>()
            mockPatchCaptures(capturedPatch, createSuccessResponse())

            // when
            devWorkspacePatch.applyRestart(namespace, workspaceName)

            // then
            val patchJson = capturedPatch.captured.value
            val patchOps = mapper.readTree(patchJson) as ArrayNode
            val path = patchOps[0].get("path").asText()

            // Verify RFC 6901 JSON Pointer escaping: "/" becomes "~1"
            assertThat(path).contains("~1")
            assertThat(path).doesNotContain("che.eclipse.org/restart")
            assertThat(path).isEqualTo("/metadata/annotations/che.eclipse.org~1restart-in-progress")
        }
    }

    @Test
    fun `#applyDevfile should correctly escape forward slashes in annotation key`() {
        runBlocking {
            // given
            val capturedPatch = slot<V1Patch>()
            mockPatchCaptures(capturedPatch, createSuccessResponse())

            // when
            devWorkspacePatch.applyDevfile(namespace, workspaceName, "content")

            // then
            val patchJson = capturedPatch.captured.value
            val patchOps = mapper.readTree(patchJson) as ArrayNode
            val path = patchOps[0].get("path").asText()

            // Verify RFC 6901 JSON Pointer escaping: "/" becomes "~1"
            assertThat(path).contains("~1")
            assertThat(path).doesNotContain("che.eclipse.org/local-devfile")
            assertThat(path).isEqualTo("/metadata/annotations/che.eclipse.org~1local-devfile")
        }
    }

    private fun createSuccessResponse(): KubernetesApiResponse<DynamicKubernetesObject> {
        return mockk {
            every { isSuccess } returns true
        }
    }

    private fun createFailureResponse(code: Int, message: String): KubernetesApiResponse<DynamicKubernetesObject> {
        val mockStatus = mockk<V1Status> {
            every { this@mockk.code } returns code
            every { this@mockk.message } returns message
        }
        return mockk {
            every { isSuccess } returns false
            every { status } returns mockStatus
        }
    }

    private fun mockPatchReturns(response: KubernetesApiResponse<DynamicKubernetesObject>) {
        every {
            mockGenericApi.patch(
                any(), any(), any(), any<V1Patch>(), any<PatchOptions>()
            )
        } returns response
    }

    private fun mockPatchThrows(exception: Throwable) {
        every {
            mockGenericApi.patch(
                any(), any(), any(), any<V1Patch>(), any<PatchOptions>()
            )
        } throws exception
    }

    private fun mockPatchCaptures(capturedPatch: CapturingSlot<V1Patch>, response: KubernetesApiResponse<DynamicKubernetesObject>) {
        every {
            mockGenericApi.patch(
                any(), any(), any(), capture(capturedPatch), any<PatchOptions>()
            )
        } returns response
    }

}
