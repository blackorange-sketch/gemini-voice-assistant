package com.example.geminicontrol

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.geminicontrol.data.audio.AudioRecorderManager
import com.example.geminicontrol.data.audio.AudioTrackPlayer
import com.example.geminicontrol.data.network.GeminiWebSocketClient
import com.example.geminicontrol.domain.tools.ToolRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private var hasAudioPermission by mutableStateOf(false)
    private var isConnected by mutableStateOf(false)
    private val logs = mutableStateListOf<String>()
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
                        logs = logs,
                        onRequestPermission = { requestAudioPermission() },
                        onStartService = { apiKey, modelName -> startGeminiLive(apiKey, modelName) }
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

    private fun addLog(msg: String) {
        lifecycleScope.launch(Dispatchers.Main) {
            logs.add(0, msg)
        }
    }

    private fun startGeminiLive(apiKey: String, modelName: String) {
        logs.clear()
        addLog("Запуск сервісу...")

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val audioRecorder = AudioRecorderManager()
                val audioPlayer = AudioTrackPlayer()
                val toolRegistry = ToolRegistry(this@MainActivity)

                webSocketClient = GeminiWebSocketClient(
                    apiKey = apiKey.trim(),
                    modelName = modelName.trim(),
                    audioRecorder = audioRecorder,
                    audioPlayer = audioPlayer,
                    toolRegistry = toolRegistry,
                    onLog = { msg -> addLog(msg) }
                )

                withContext(Dispatchers.Main) { isConnected = true }

                webSocketClient?.connectAndStart()

            } catch (e: Throwable) {
                e.printStackTrace()
                addLog("Критична помилка: ${e.localizedMessage ?: e.toString()}")
                withContext(Dispatchers.Main) { isConnected = false }
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
    logs: List<String>,
    onRequestPermission: () -> Unit,
    onStartService: (String, String) -> Unit
) {
    var apiKey by remember { mutableStateOf("") }
    var modelName by remember { mutableStateOf("gemini-2.0-flash-exp") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Gemini Voice Control",
            style = MaterialTheme.typography.headlineSmall
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("Gemini API Key") },
            singleLine = true,
            enabled = !isConnected,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = modelName,
            onValueChange = { modelName = it },
            label = { Text("Модель (напр. gemini-2.0-flash-exp)") },
            singleLine = true,
            enabled = !isConnected,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        if (hasPermission) {
            Button(
                onClick = { onStartService(apiKey, modelName) },
                enabled = apiKey.isNotBlank() && modelName.isNotBlank() && !isConnected,
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

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Консоль подій:",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.align(Alignment.Start)
        )

        Spacer(modifier = Modifier.height(4.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color(0xFF1E1E1E))
                .padding(8.dp)
        ) {
            LazyColumn {
                items(logs) { log ->
                    Text(
                        text = "> $log",
                        color = if (log.contains("Помилка") || log.contains("Причина")) Color.Red else Color.Green,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }
        }
    }
}
