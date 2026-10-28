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

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.google.gson.JsonParser
import com.intellij.testFramework.LoggedErrorProcessor
import io.kubernetes.client.custom.V1Patch
import io.kubernetes.client.openapi.models.V1Status
import io.kubernetes.client.util.generic.KubernetesApiResponse
import io.kubernetes.client.util.generic.dynamic.DynamicKubernetesApi
import io.kubernetes.client.util.generic.dynamic.DynamicKubernetesObject
import io.kubernetes.client.util.generic.options.PatchOptions
import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DevWorkspacePatchTest {

    private lateinit var mockGenericApi: DynamicKubernetesApi
    private lateinit var mapper: ObjectMapper
    private lateinit var devWorkspacePatch: DevWorkspacePatch

    private val namespace = "test-namespace"
    private val workspaceName = "test-workspace"

    private val pendingTemplateKey = "che.eclipse.org/pending-devfile-template"
    private val pendingTemplatePath =
        "/metadata/annotations/che.eclipse.org~1pending-devfile-template"
    private val restartPath = "/metadata/annotations/che.eclipse.org~1restart-in-progress"

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
    fun `#applyDevfileAndRestart publishes the payload and the restart annotation in one patch`() = runBlocking<Unit> {
        // given
        mockWorkspace(workspaceJson())
        val capturedPatch = slot<V1Patch>()
        mockPatchCaptures(capturedPatch)

        // when
        applyDevfileAndRestart(namespace, workspaceName, devfileContent)

        // then
        val ops = mapper.readTree(capturedPatch.captured.value) as ArrayNode
        assertThat(ops).hasSize(2)
        assertThat(ops.map { it.get("path").asText() })
            .containsExactlyInAnyOrder(pendingTemplatePath, restartPath)
        assertThat(ops.map { it.get("path").asText() })
            .doesNotContain("/spec/template")

        val payload = mapper.readTree(payloadFrom(capturedPatch.captured))
        assertThat(payload.get("version").asInt()).isEqualTo(1)
        assertThat(payload.get("template").get("components").isArray).isTrue()

        verify(exactly = 1) {
            mockGenericApi.patch(
                namespace, workspaceName, V1Patch.PATCH_FORMAT_JSON_PATCH, any<V1Patch>(), any<PatchOptions>()
            )
        }
    }

    @Test
    fun `#applyDevfileAndRestart adds the annotations map when the workspace has none`() = runBlocking<Unit> {
        // given
        mockWorkspace(workspaceJson(annotations = null))
        val capturedPatch = slot<V1Patch>()
        mockPatchCaptures(capturedPatch)

        // when
        applyDevfileAndRestart(namespace, workspaceName, devfileContent)

        // then
        val ops = mapper.readTree(capturedPatch.captured.value) as ArrayNode
        assertThat(ops).hasSize(1)
        val annotationOp = ops[0]
        assertThat(annotationOp.get("path").asText()).isEqualTo("/metadata/annotations")
        assertThat(annotationOp.get("value").get("che.eclipse.org/restart-in-progress").asText())
            .isEqualTo("true")
        assertThat(annotationOp.get("value").get(pendingTemplateKey).asText()).isNotBlank()
    }

    @Test
    fun `#applyDevfileAndRestart publishes a template when the workspace has none`() = runBlocking<Unit> {
        // given
        mockWorkspace(workspaceJson(template = null))
        val capturedPatch = slot<V1Patch>()
        mockPatchCaptures(capturedPatch)

        // when
        applyDevfileAndRestart(namespace, workspaceName, devfileContent)

        // then
        val template = mapper.readTree(payloadFrom(capturedPatch.captured)).get("template")
        assertThat(template.get("components")[0].get("name").asText()).isEqualTo("tooling")
    }

    @Test
    fun `#applyDevfileAndRestart drops devfile schemaVersion and metadata`() = runBlocking<Unit> {
        // given
        mockWorkspace(workspaceJson())
        val capturedPatch = slot<V1Patch>()
        mockPatchCaptures(capturedPatch)

        // when
        applyDevfileAndRestart(namespace, workspaceName, devfileContent)

        // then
        val template = templateFrom(capturedPatch.captured)
        assertThat(template.has("schemaVersion")).isFalse()
        assertThat(template.has("metadata")).isFalse()
        assertThat(template.get("components").isArray).isTrue()
    }

    @Test
    fun `#applyDevfileAndRestart carries over projects and attributes`() = runBlocking<Unit> {
        // given
        mockWorkspace(workspaceJson())
        val capturedPatch = slot<V1Patch>()
        mockPatchCaptures(capturedPatch)

        // when
        applyDevfileAndRestart(namespace, workspaceName, devfileContent)

        // then
        val template = templateFrom(capturedPatch.captured)
        assertThat(template.get("projects")).hasSize(1)
        assertThat(template.get("projects")[0].get("name").asText()).isEqualTo("project")
        assertThat(template.get("attributes").get("from-workspace").asText()).isEqualTo("yes")
    }

    @Test
    fun `#applyDevfileAndRestart carries over Che-injected env vars`() = runBlocking<Unit> {
        // given
        mockWorkspace(workspaceJson())
        val capturedPatch = slot<V1Patch>()
        mockPatchCaptures(capturedPatch)

        // when
        applyDevfileAndRestart(namespace, workspaceName, devfileContent)

        // then
        val env = templateFrom(capturedPatch.captured)
            .get("components")[0].get("container").get("env")
        val envNames = env.map { it.get("name").asText() }
        assertThat(envNames).containsExactlyInAnyOrder("CHE_DASHBOARD_URL", "USER_VAR")
    }

    @Test
    fun `#applyDevfileAndRestart throws without patching when the annotations would exceed the limit`() {
        // given an annotation that leaves too little room for the payload
        mockWorkspace(workspaceJson(annotations = mapOf("huge" to "x".repeat(260 * 1024))))

        // when / then
        assertThatThrownBy {
            runBlocking { applyDevfileAndRestart(namespace, workspaceName, devfileContent) }
        }
            .isInstanceOf(DevfileValidationException::class.java)
            .hasMessageContaining("too large")
        verify(exactly = 0) { mockGenericApi.patch(any(), any(), any(), any<V1Patch>(), any<PatchOptions>()) }
    }

    @Test
    fun `#applyDevfileAndRestart throws without API calls for invalid YAML`() {
        // when / then
        assertThatThrownBy {
            runBlocking { applyDevfileAndRestart(namespace, workspaceName, "components: [") }
        }
            .isInstanceOf(DevfileValidationException::class.java)
            .hasMessage("Devfile is not valid YAML.")
        verify(exactly = 0) { mockGenericApi.get(any<String>(), any<String>()) }
        verify(exactly = 0) { mockGenericApi.patch(any(), any(), any(), any<V1Patch>(), any<PatchOptions>()) }
    }

    @Test
    fun `#applyDevfileAndRestart throws without patching when devfile has no container`() {
        // given
        val content = """
            schemaVersion: 2.2.0
            metadata:
              name: my-workspace
        """.trimIndent()
        mockWorkspace(workspaceJson())

        // when / then
        assertThatThrownBy {
            runBlocking { applyDevfileAndRestart(namespace, workspaceName, content) }
        }
            .isInstanceOf(DevfileValidationException::class.java)
            .hasMessage("Devfile defines no container component.")
        verify(exactly = 0) { mockGenericApi.patch(any(), any(), any(), any<V1Patch>(), any<PatchOptions>()) }
    }

    @Test
    fun `#applyDevfileAndRestart throws when the workspace cannot be read`() {
        ignoringLoggedErrors {
            // given
            every { mockGenericApi.get(namespace, workspaceName) } returns createFailureResponse(403, "Forbidden")

            // when / then
            val exception = assertThrows(RestartException::class.java) {
                runBlocking { applyDevfileAndRestart(namespace, workspaceName, devfileContent) }
            }
            assertThat(exception)
                .hasMessageContaining("Could not prepare the restart of workspace")
            assertThat(exception.reason)
                .isEqualTo(RestartException.Reason.RESTART_PREPARATION_FAILED)
            verify(exactly = 0) { mockGenericApi.patch(any(), any(), any(), any<V1Patch>(), any<PatchOptions>()) }
        }
    }

    @Test
    fun `#applyDevfileAndRestart throws when the patch fails`() {
        ignoringLoggedErrors {
            // given
            mockWorkspace(workspaceJson())
            mockPatchReturns(createFailureResponse(409, "Conflict"))

            // when / then
            val exception = assertThrows(RestartException::class.java) {
                runBlocking { applyDevfileAndRestart(namespace, workspaceName, devfileContent) }
            }
            assertThat(exception)
                .hasMessageContaining("Could not apply the devfile to workspace")
            assertThat(exception.reason).isEqualTo(RestartException.Reason.PUBLISH_FAILED)
        }
    }

    @Test
    fun `#applyDevfileAndRestart throws when the patch throws`() {
        ignoringLoggedErrors {
            // given
            mockWorkspace(workspaceJson())
            every {
                mockGenericApi.patch(any(), any(), any(), any<V1Patch>(), any<PatchOptions>())
            } throws RuntimeException("Network error")

            // when / then
            val exception = assertThrows(RestartException::class.java) {
                runBlocking { applyDevfileAndRestart(namespace, workspaceName, devfileContent) }
            }
            assertThat(exception)
                .hasMessageContaining("Could not apply the devfile to workspace")
            assertThat(exception.reason).isEqualTo(RestartException.Reason.PUBLISH_FAILED)
        }
    }

    @Test
    fun `#applyDevfileAndRestart removes a stale restart annotation first`() = runBlocking<Unit> {
        // given a workspace that still carries a leftover restart annotation
        mockWorkspace(
            workspaceJson(
                annotations = mapOf(
                    "che.eclipse.org/che-editor" to "che-idea-server",
                    "che.eclipse.org/restart-in-progress" to "true"
                )
            )
        )
        val capturedPatches = mutableListOf<V1Patch>()
        every {
            mockGenericApi.patch(any(), any(), any(), any<V1Patch>(), any<PatchOptions>())
        } answers {
            capturedPatches.add(invocation.args[3] as V1Patch)
            createEmptySuccessResponse()
        }

        // when
        applyDevfileAndRestart(namespace, workspaceName, devfileContent)

        // then the stale annotation is removed in its own request, so the Gateway sees a transition
        assertThat(capturedPatches).hasSize(2)
        val removeOps = mapper.readTree(capturedPatches[0].value) as ArrayNode
        assertThat(removeOps).hasSize(1)
        assertThat(removeOps[0].get("op").asText()).isEqualTo("remove")
        assertThat(removeOps[0].get("path").asText()).isEqualTo(restartPath)
        val publishOps = mapper.readTree(capturedPatches[1].value) as ArrayNode
        assertThat(publishOps).hasSize(2)
    }

    @Test
    fun `#applyDevfileAndRestart sends one patch when no restart annotation is present`() = runBlocking<Unit> {
        // given
        mockWorkspace(workspaceJson())
        val capturedPatches = mutableListOf<V1Patch>()
        every {
            mockGenericApi.patch(any(), any(), any(), any<V1Patch>(), any<PatchOptions>())
        } answers {
            capturedPatches.add(invocation.args[3] as V1Patch)
            createEmptySuccessResponse()
        }

        // when
        applyDevfileAndRestart(namespace, workspaceName, devfileContent)

        // then
        assertThat(capturedPatches).hasSize(1)
    }

    @Test
    fun `#planRestart reports no changes when the devfile matches the workspace`() = runBlocking<Unit> {
        // given
        mockWorkspace(workspaceJson())

        // when
        val plan = devWorkspacePatch.planRestart(namespace, workspaceName, unchangedDevfileContent)

        // then
        assertThat(plan.hasChanges).isFalse()
        verify(exactly = 0) {
            mockGenericApi.patch(any(), any(), any(), any<V1Patch>(), any<PatchOptions>())
        }
    }

    @Test
    fun `#planRestart reports changes when the devfile differs from the workspace`() = runBlocking<Unit> {
        // given
        mockWorkspace(workspaceJson())

        // when
        val plan = devWorkspacePatch.planRestart(namespace, workspaceName, devfileContent)

        // then
        assertThat(plan.hasChanges).isTrue()
    }

    @Test
    fun `#planRestart reports changes when the workspace has no template`() = runBlocking<Unit> {
        // given
        mockWorkspace(workspaceJson(template = null))

        // when
        val plan = devWorkspacePatch.planRestart(namespace, workspaceName, devfileContent)

        // then
        assertThat(plan.hasChanges).isTrue()
    }

    @Test
    fun `#planRestart throws when the workspace cannot be read`() {
        ignoringLoggedErrors {
            // given
            every { mockGenericApi.get(namespace, workspaceName) } returns createFailureResponse(403, "Forbidden")

            // when / then
            val exception = assertThrows(RestartException::class.java) {
                runBlocking { devWorkspacePatch.planRestart(namespace, workspaceName, devfileContent) }
            }
            assertThat(exception)
                .hasMessageContaining("Could not prepare the restart of workspace")
            assertThat(exception.reason)
                .isEqualTo(RestartException.Reason.RESTART_PREPARATION_FAILED)
        }
    }

    @Test
    fun `#publishRestart sends the payload it is given`() = runBlocking<Unit> {
        // given
        mockWorkspace(workspaceJson())
        val capturedPatch = slot<V1Patch>()
        mockPatchCaptures(capturedPatch)

        // when
        devWorkspacePatch.publishRestart(namespace, workspaceName, storedPayload)

        // then
        assertThat(payloadFrom(capturedPatch.captured)).isEqualTo(storedPayload)
    }

    private suspend fun applyDevfileAndRestart(
        namespace: String,
        name: String,
        devfileContent: String
    ) {
        val plan = devWorkspacePatch.planRestart(namespace, name, devfileContent)
        devWorkspacePatch.publishRestart(namespace, name, plan.payload)
    }

    private val unchangedDevfileContent = """
        schemaVersion: 2.2.0
        metadata:
          name: my-workspace
        components:
          - name: tooling
            container:
              image: old-image
    """.trimIndent()

    private val storedPayload =
        """{"version":1,"template":{"components":[{"name":"stored"}]}}"""

    private val devfileContent = """
        schemaVersion: 2.2.0
        metadata:
          name: changed-name
        components:
          - name: tooling
            container:
              image: new-image
              env:
                - name: USER_VAR
                  value: new
    """.trimIndent()

    private fun payloadFrom(patch: V1Patch): String {
        val ops = mapper.readTree(patch.value) as ArrayNode
        val op = ops.first { it.get("path").asText().contains("pending-devfile-template") }
        return if (op.get("path").asText() == "/metadata/annotations") {
            op.get("value").get(pendingTemplateKey).asText()
        } else {
            op.get("value").asText()
        }
    }

    private fun templateFrom(patch: V1Patch): JsonNode {
        return mapper.readTree(payloadFrom(patch)).get("template")
    }

    private fun workspaceJson(
        template: Map<String, Any?>? = mapOf(
            "components" to listOf(
                mapOf(
                    "name" to "tooling",
                    "container" to mapOf(
                        "image" to "old-image",
                        "env" to listOf(
                            mapOf("name" to "CHE_DASHBOARD_URL", "value" to "https://dashboard")
                        )
                    )
                )
            ),
            "projects" to listOf(mapOf("name" to "project")),
            "attributes" to mapOf("from-workspace" to "yes")
        ),
        annotations: Map<String, String>? = mapOf("che.eclipse.org/che-editor" to "che-idea-server")
    ): String {
        val metadata = linkedMapOf<String, Any?>("name" to workspaceName, "namespace" to namespace)
        annotations?.let { metadata["annotations"] = it }
        val spec = linkedMapOf<String, Any?>()
        template?.let { spec["template"] = it }
        return mapper.writeValueAsString(mapOf("metadata" to metadata, "spec" to spec))
    }

    private fun mockWorkspace(workspaceJson: String) {
        every { mockGenericApi.get(namespace, workspaceName) } returns createSuccessResponse(workspaceJson)
    }

    private fun createSuccessResponse(workspaceJson: String): KubernetesApiResponse<DynamicKubernetesObject> {
        val raw = JsonParser.parseString(workspaceJson).asJsonObject
        return KubernetesApiResponse(DynamicKubernetesObject(raw))
    }

    private fun createFailureResponse(code: Int, message: String): KubernetesApiResponse<DynamicKubernetesObject> {
        val status = V1Status().apply {
            this.code = code
            this.message = message
        }
        return KubernetesApiResponse(status, code)
    }

    private fun mockPatchReturns(response: KubernetesApiResponse<DynamicKubernetesObject>) {
        every {
            mockGenericApi.patch(any(), any(), any(), any<V1Patch>(), any<PatchOptions>())
        } returns response
    }

    private fun mockPatchCaptures(
        capturedPatch: CapturingSlot<V1Patch>,
        response: KubernetesApiResponse<DynamicKubernetesObject> = createEmptySuccessResponse()
    ) {
        every {
            mockGenericApi.patch(any(), any(), any(), capture(capturedPatch), any<PatchOptions>())
        } returns response
    }

    private fun createEmptySuccessResponse(): KubernetesApiResponse<DynamicKubernetesObject> {
        return KubernetesApiResponse(DynamicKubernetesObject())
    }

    private fun ignoringLoggedErrors(body: () -> Unit) {
        LoggedErrorProcessor.executeWith<RuntimeException>(object : LoggedErrorProcessor() {
            override fun processError(
                category: String,
                message: String,
                details: Array<out String>,
                t: Throwable?
            ): Set<Action> {
                return Action.NONE
            }
        }) {
            body()
        }
    }
}
