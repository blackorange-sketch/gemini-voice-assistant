package com.example.geminicontrol

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.geminicontrol.data.audio.AudioRecorderManager
import com.example.geminicontrol.data.audio.AudioTrackPlayer
import com.example.geminicontrol.data.network.GeminiWebSocketClient
import com.example.geminicontrol.domain.tools.ToolRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var hasAudioPermission by mutableStateOf(false)
    private var isConnected by mutableStateOf(false)
    private var webSocketClient: GeminiWebSocketClient? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        hasAudioPermission = isGranted
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkAudioPermission()

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        hasPermission = hasAudioPermission,
                        isConnected = isConnected,
                        onRequestPermission = { requestAudioPermission() },
                        onStartService = { apiKey -> startGeminiLive(apiKey) }
                    )
                }
            }
        }
    }

    private fun checkAudioPermission() {
        hasAudioPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestAudioPermission() {
        requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startGeminiLive(apiKey: String) {
        val audioRecorder = AudioRecorderManager()
        val audioPlayer = AudioTrackPlayer()
        val toolRegistry = ToolRegistry(this)

        webSocketClient = GeminiWebSocketClient(
            apiKey = apiKey,
            audioRecorder = audioRecorder,
            audioPlayer = audioPlayer,
            toolRegistry = toolRegistry
        )

        lifecycleScope.launch(Dispatchers.IO) {
            isConnected = true
            try {
                webSocketClient?.connectAndStart()
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isConnected = false
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        webSocketClient?.close()
    }
}

@Composable
fun MainScreen(
    hasPermission: Boolean,
    isConnected: Boolean,
    onRequestPermission: () -> Unit,
    onStartService: (String) -> Unit
) {
    var apiKey by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Gemini Voice Control",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(24.dp))

        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("Gemini API Key") },
            singleLine = true,
            enabled = !isConnected,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (hasPermission) {
            Button(
                onClick = { onStartService(apiKey) },
                enabled = apiKey.isNotBlank() && !isConnected,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isConnected) "З'єднано (Слухаю...)" else "Запустити асистента")
            }
        } else {
            Button(
                onClick = onRequestPermission,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Надати дозвіл на мікрофон")
            }
        }
    }
}
