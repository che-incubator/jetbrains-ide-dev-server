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
package io.github.che.userActivity

import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI
import java.net.URL
import javax.swing.Timer

@Service(Service.Level.PROJECT)
class UserActivityService {

    private var lastActivityTime: Long = System.currentTimeMillis()

    private val inactivityThresholdMs = 5 * 60 * 1000 // 5 minutes

    private val timer = Timer(10_000) { // check every 10 seconds
        val now = System.currentTimeMillis()
        if (now - lastActivityTime > inactivityThresholdMs) {
//            the user is inactive for more than 5 minutes
        } else {
            sendActivityTick()
        }
    }

    private val httpClient = OkHttpClient()

    private val machineExecURL: URL = getMachineExecURL()

    init {
        setupActivityListeners()
        timer.start()
    }

    private fun setupActivityListeners() {
        val eventMulticaster = EditorFactory.getInstance().eventMulticaster
        eventMulticaster.addCaretListener(object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) = updateLastActivityTime()
        }) {}
    }

    private fun updateLastActivityTime() {
        lastActivityTime = System.currentTimeMillis()
    }

    private fun getMachineExecURL(): URL {
        val port: String = System.getenv("MACHINE_EXEC_PORT") ?: "3333"
        val machineExecURI = URI.create("http://127.0.0.1:$port/activity/tick")
        return machineExecURI.toURL()
    }

    private fun sendActivityTick() {
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = "stub".toRequestBody(mediaType)

        val request = Request.Builder()
            .url(machineExecURL)
            .post(body)
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Failed sending activity tick to the machine-exec server: $response")
        }
    }
}
