package com.example.geminicontrol.domain.tools

import android.content.Context
import android.hardware.camera2.CameraManager

class ToolRegistry(private val context: Context) {

    fun executeTool(name: String, argsJson: String): String {
        return when (name) {
            "toggle_flashlight" -> {
                val enabled = argsJson.contains("true")
                toggleFlashlight(enabled)
            }
            else -> "{\"status\": \"error\", \"message\": \"Unknown tool\"}"
        }
    }

    private fun toggleFlashlight(enabled: Boolean): String {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList[0]
            cameraManager.setTorchMode(cameraId, enabled)
            "{\"status\": \"success\", \"flashlight_enabled\": $enabled}"
        } catch (e: Exception) {
            "{\"status\": \"error\", \"message\": \"${e.localizedMessage}\"}"
        }
    }
}
