package com.soundtag.app.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.soundtag.app.recording.RecordingState
import com.soundtag.app.util.formatDuration
import kotlinx.coroutines.flow.SharedFlow

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onRequestPermissions: () -> Unit,
    onStartRecording: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    ObserveEvents(viewModel.events, snackbarHostState)

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
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

            if (!uiState.permissionsGranted) {
                PermissionsCard(onRequestPermissions = onRequestPermissions)
            }

            RecordButton(
                recordingState = uiState.recordingState,
                permissionsGranted = uiState.permissionsGranted,
                onStart = onStartRecording,
                onStop = { viewModel.onStopRecordingRequested() }
            )

            RecordingStatusText(uiState.recordingState)
        }
    }

    uiState.saveDialog?.let { dialog ->
        SaveDialog(
            dialog = dialog,
            onFilenameChanged = viewModel::onSaveFilenameChanged,
            onConfirm = viewModel::onSaveConfirmed,
            onDismiss = viewModel::onSaveDialogDismissed
        )
    }
}

@Composable
private fun ObserveEvents(events: SharedFlow<String>, snackbarHostState: SnackbarHostState) {
    LaunchedEffect(events) {
        events.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }
}

@Composable
private fun PermissionsCard(onRequestPermissions: () -> Unit) {
    Card(shape = RoundedCornerShape(16.dp)) {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surface)
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(text = "Permissions required", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(
                text = "SoundTag needs microphone, location, and notification permissions to record reliably in the background.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
            )
            Button(onClick = onRequestPermissions) {
                Text(text = "Grant permissions")
            }
        }
    }
}

@Composable
private fun RecordButton(
    recordingState: RecordingState,
    permissionsGranted: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    val isRecording = recordingState is RecordingState.Recording
    val scale by animateFloatAsState(
        targetValue = if (isRecording) 1.08f else 1f,
        animationSpec = tween(durationMillis = 900, easing = FastOutSlowInEasing),
        label = "recordButtonScale"
    )

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(210.dp)
                .scale(scale)
                .background(
                    color = if (isRecording) Color(0xFFEF4444).copy(alpha = 0.15f)
                            else Color(0xFF38BDF8).copy(alpha = 0.12f),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Button(
                onClick = { if (isRecording) onStop() else onStart() },
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
}

@Composable
private fun RecordingStatusText(recordingState: RecordingState) {
    val statusText = when (recordingState) {
        is RecordingState.Recording -> "Recording • ${formatDuration(recordingState.durationSeconds)}"
        is RecordingState.Error -> "Error: ${recordingState.message}"
        else -> "Ready to record"
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                text = "Recording in background — tap stop to save.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun SaveDialog(
    dialog: SaveDialogState,
    onFilenameChanged: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "Save recording") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text = "Enter a filename prefix. Saved as .m4a with a JSON sidecar.")
                OutlinedTextField(
                    value = dialog.filenameInput,
                    onValueChange = onFilenameChanged,
                    label = { Text("Filename") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(text = "Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = "Cancel") }
        }
    )
}
