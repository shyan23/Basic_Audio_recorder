package com.soundtag.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberScaffoldState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.soundtag.app.recording.RecordingService
import com.soundtag.app.recording.RecordingState
import com.soundtag.app.storage.FileSaver
import com.soundtag.app.storage.MetadataWriter
import com.soundtag.app.ui.theme.SoundTagTheme
import kotlinx.coroutines.launch
import java.io.File
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    private var recordingService: RecordingService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            recordingService = (service as RecordingService.LocalBinder).getService()
            serviceBound = true
        }

        override fun onServiceDisconnected(name: ComponentName) {
            recordingService = null
            serviceBound = false
        }
    }

    private val requiredPermissions: List<String>
        get() = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            SoundTagTheme {
                val recordingState by RecordingService.state.collectAsStateWithLifecycle()
                val scaffoldState = rememberScaffoldState()
                val snackbarHostState = remember { SnackbarHostState() }
                val coroutineScope = rememberCoroutineScope()
                var permissionStatus by remember { mutableStateOf(mapOf<String, Boolean>()) }
                var saveDialogVisible by remember { mutableStateOf(false) }
                var pendingAudioFile by remember { mutableStateOf<File?>(null) }
                var pendingRecordingState by remember { mutableStateOf<RecordingState.Recording?>(null) }
                var filenameInput by remember { mutableStateOf("") }
                val permissionsGranted = requiredPermissions.all { permissionStatus[it] == true }

                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { results ->
                    permissionStatus = results
                }

                LaunchedEffect(Unit) {
                    permissionStatus = requiredPermissions.associateWith {
                        ContextCompat.checkSelfPermission(this@MainActivity, it) == PackageManager.PERMISSION_GRANTED
                    }
                }

                fun requestPermissions() {
                    permissionLauncher.launch(requiredPermissions.toTypedArray())
                }

                fun startRecording() {
                    if (!permissionsGranted) {
                        requestPermissions()
                        return
                    }
                    val intent = Intent(this, RecordingService::class.java).apply {
                        action = RecordingService.ACTION_START_RECORDING
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        ContextCompat.startForegroundService(this, intent)
                    } else {
                        startService(intent)
                    }
                }

                fun stopRecording() {
                    val current = recordingState as? RecordingState.Recording
                    pendingRecordingState = current
                    val audioFile = recordingService?.stopRecording()
                    if (audioFile != null) {
                        pendingAudioFile = audioFile
                        filenameInput = buildSuggestedFilename(current)
                        saveDialogVisible = true
                    } else {
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar("Unable to stop recording")
                        }
                    }
                }

                fun saveRecording() {
                    val audioFile = pendingAudioFile
                    val recording = pendingRecordingState
                    if (audioFile == null || recording == null) {
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar("Unable to save recording")
                        }
                        saveDialogVisible = false
                        return
                    }

                    val desiredName = if (filenameInput.endsWith(".m4a")) filenameInput else "$filenameInput.m4a"
                    val metadata = MetadataWriter.buildJson(desiredName, recording, ZonedDateTime.now())

                    coroutineScope.launch {
                        val uri = FileSaver.saveRecording(this@MainActivity, audioFile, desiredName, metadata)
                        if (uri != null) {
                            snackbarHostState.showSnackbar("Saved $desiredName")
                        } else {
                            snackbarHostState.showSnackbar("Failed to save recording")
                        }
                    }
                    saveDialogVisible = false
                }

                Scaffold(
                    scaffoldState = scaffoldState,
                    snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
                ) { padding ->
                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(20.dp)
                        ) {
                            Text(
                                text = "SoundTag",
                                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.ExtraBold)
                            )

                            Text(
                                text = "A minimal recorder that saves audio with GPS metadata and JSON sidecars.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f)
                            )

                            if (!permissionsGranted) {
                                Card(
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .background(MaterialTheme.colorScheme.surface)
                                            .padding(18.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Text(
                                            text = "Permissions required",
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 16.sp
                                        )
                                        Text(
                                            text = "SoundTag needs microphone, location, and notification permissions to record reliably in the background.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                                        )
                                        Button(onClick = { requestPermissions() }) {
                                            Text(text = "Grant permissions")
                                        }
                                    }
                                }
                            }

                            val isRecording = recordingState is RecordingState.Recording
                            val statusText = when (recordingState) {
                                is RecordingState.Recording -> "Recording • ${formatDuration(recordingState.durationSeconds)}"
                                is RecordingState.Error -> "Error: ${recordingState.message}"
                                else -> "Ready to record"
                            }

                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                val scale by animateFloatAsState(
                                    targetValue = if (isRecording) 1.08f else 1f,
                                    animationSpec = tween(durationMillis = 900, easing = FastOutSlowInEasing)
                                )

                                Box(
                                    modifier = Modifier
                                        .size(210.dp)
                                        .scale(scale)
                                        .background(
                                            color = if (isRecording) Color(0xFFEF4444).copy(alpha = 0.15f) else Color(0xFF38BDF8).copy(alpha = 0.12f),
                                            shape = CircleShape
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Button(
                                        onClick = {
                                            if (isRecording) stopRecording() else startRecording()
                                        },
                                        shape = CircleShape,
                                        modifier = Modifier.size(150.dp),
                                        enabled = permissionsGranted || isRecording
                                    ) {
                                        Text(
                                            text = if (isRecording) "Stop" else "Record",
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                            }

                            Column(
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    text = statusText,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Medium
                                )

                                if (recordingState is RecordingState.Recording) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(
                                            text = recordingState.location?.latitude?.let { "Lat: %.5f".format(it) } ?: "Lat: unknown",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        Text(
                                            text = recordingState.location?.longitude?.let { "Lng: %.5f".format(it) } ?: "Lng: unknown",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    Text(
                                        text = "Recording in background — tap stop to save metadata.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        }
                    }
                }

                if (saveDialogVisible) {
                    AlertDialog(
                        onDismissRequest = { saveDialogVisible = false },
                        title = { Text(text = "Save recording") },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(text = "Enter a filename prefix. The file will be saved as .m4a with a JSON sidecar.")
                                OutlinedTextField(
                                    value = filenameInput,
                                    onValueChange = { filenameInput = it },
                                    label = { Text("Filename") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { saveRecording() }) {
                                Text(text = "Save")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { saveDialogVisible = false }) {
                                Text(text = "Cancel")
                            }
                        }
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Intent(this, RecordingService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }

    private fun buildSuggestedFilename(recording: RecordingState.Recording?): String {
        val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").format(ZonedDateTime.now())
        return "misc_$timestamp"
    }

    private fun formatDuration(seconds: Long): String {
        val minutes = seconds / 60
        val remainder = seconds % 60
        return "%02d:%02d".format(minutes, remainder)
    }
}
