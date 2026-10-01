package com.example.geminicontrol.data.network

import android.util.Base64
import com.example.geminicontrol.data.audio.AudioRecorderManager
import com.example.geminicontrol.data.audio.AudioTrackPlayer
import com.example.geminicontrol.domain.tools.ToolRegistry
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.cancellable
import kotlinx.serialization.json.*

class GeminiWebSocketClient(
    private val apiKey: String,
    private val audioRecorder: AudioRecorderManager,
    private val audioPlayer: AudioTrackPlayer,
    private val toolRegistry: ToolRegistry,
    private val onLog: (String) -> Unit
) {
    private val client = HttpClient(CIO) {
        install(WebSockets)
    }

    private var session: DefaultClientWebSocketSession? = null

    suspend fun connectAndStart() = withContext(Dispatchers.IO) {
        val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent?key=$apiKey"

        onLog("Підключення до сокета...")
        audioPlayer.start()

        client.webSocket(url) {
            session = this
            onLog("WebSocket з'єднано!")

            sendSetupFrame()
            onLog("Setup-фрейм відправлено")

            var chunksSent = 0
            val recordJob = launch {
                audioRecorder.startRecording().cancellable().collect { pcmChunk ->
                    sendAudioChunk(pcmChunk)
                    chunksSent++
                    if (chunksSent % 20 == 0) {
                        onLog("Відправлено $chunksSent аудіо-фрагментів")
                    }
                }
            }

            try {
                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        val text = frame.readText()
                        handleServerMessage(text)
                    }
                }
            } catch (e: Exception) {
                onLog("Помилка сокета: ${e.localizedMessage}")
            } finally {
                recordJob.cancel()
                audioPlayer.stop()
                onLog("З'єднання закрито")
            }
        }
    }

    private suspend fun sendSetupFrame() {
        val setupJson = buildJsonObject {
            putJsonObject("setup") {
                put("model", "models/gemini-2.0-flash-exp")
                putJsonObject("generation_config") {
                    putJsonArray("response_modalities") {
                        add("AUDIO")
                    }
                }
                putJsonArray("tools") {
                    addJsonObject {
                        putJsonArray("function_declarations") {
                            addJsonObject {
                                put("name", "toggle_flashlight")
                                put("description", "Увімкнути або вимкнути ліхтарик смартфона")
                                putJsonObject("parameters") {
                                    put("type", "OBJECT")
                                    putJsonObject("properties") {
                                        putJsonObject("enabled") {
                                            put("type", "BOOLEAN")
                                            put("description", "true щоб увімкнути, false щоб вимкнути")
                                        }
                                    }
                                    putJsonArray("required") { add("enabled") }
                                }
                            }
                        }
                    }
                }
            }
        }
        session?.send(Frame.Text(setupJson.toString()))
    }

    private suspend fun sendAudioChunk(pcmData: ByteArray) {
        val base64Audio = Base64.encodeToString(pcmData, Base64.NO_WRAP)
        val realtimeInput = buildJsonObject {
            putJsonObject("realtime_input") {
                putJsonArray("media_chunks") {
                    addJsonObject {
                        put("mime_type", "audio/pcm;rate=16000")
                        put("data", base64Audio)
                    }
                }
            }
        }
        session?.send(Frame.Text(realtimeInput.toString()))
    }

    private suspend fun handleServerMessage(jsonText: String) {
        val json = Json.parseToJsonElement(jsonText).jsonObject

        if (json.containsKey("error")) {
            val errMsg = json["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content ?: jsonText
            onLog("Помилка від Gemini: $errMsg")
            return
        }

        var receivedAudio = false
        json["serverContent"]?.jsonObject?.get("modelTurn")?.jsonObject?.get("parts")?.jsonArray?.forEach { part ->
            part.jsonObject["inlineData"]?.jsonObject?.let { inlineData ->
                val base64Data = inlineData["data"]?.jsonPrimitive?.content ?: ""
                val pcmBytes = Base64.decode(base64Data, Base64.DEFAULT)
                audioPlayer.playChunk(pcmBytes)
                receivedAudio = true
            }
        }
        if (receivedAudio) {
            onLog("Отримано аудіо-відповідь від Gemini")
        }

        json["toolCall"]?.jsonObject?.get("functionCalls")?.jsonArray?.forEach { call ->
            val callObj = call.jsonObject
            val callId = callObj["id"]?.jsonPrimitive?.content ?: ""
            val functionName = callObj["name"]?.jsonPrimitive?.content ?: ""
            val args = callObj["args"]?.toString() ?: "{}"

            onLog("Gemini викликає функцію: $functionName($args)")
            val resultJson = toolRegistry.executeTool(functionName, args)
            onLog("Результат виконання: $resultJson")

            sendToolResponse(callId, resultJson)
        }
    }

    private suspend fun sendToolResponse(callId: String, resultJson: String) {
        val toolResponse = buildJsonObject {
            putJsonObject("tool_response") {
                putJsonArray("function_responses") {
                    addJsonObject {
                        put("id", callId)
                        put("response", Json.parseToJsonElement(resultJson))
                    }
                }
            }
        }
        session?.send(Frame.Text(toolResponse.toString()))
    }

    fun close() {
        client.close()
    }
}
