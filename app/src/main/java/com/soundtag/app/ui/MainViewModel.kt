package com.soundtag.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.soundtag.app.location.LocationFix
import com.soundtag.app.recording.RecordingService
import com.soundtag.app.recording.RecordingState
import com.soundtag.app.storage.FileSaver
import com.soundtag.app.storage.MetadataWriter
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** One-shot events consumed by the UI for snackbar messages. */
    private val _events = MutableSharedFlow<String>()
    val events: SharedFlow<String> = _events.asSharedFlow()

    private var serviceCollectionJob: Job? = null
    private var boundService: RecordingService? = null

    fun onPermissionsResult(granted: Boolean) {
        _uiState.update { it.copy(permissionsGranted = granted) }
    }

    fun onServiceConnected(service: RecordingService) {
        boundService = service
        serviceCollectionJob = viewModelScope.launch {
            service.recordingState.collect { state ->
                _uiState.update { it.copy(recordingState = state) }
            }
        }
    }

    fun onServiceDisconnected() {
        serviceCollectionJob?.cancel()
        serviceCollectionJob = null
        boundService = null
    }

    fun onStopRecordingRequested() {
        val service = boundService ?: run {
            viewModelScope.launch { _events.emit("Service not connected") }
            return
        }
        val current = _uiState.value.recordingState as? RecordingState.Recording ?: return
        val tempFile = service.stopRecording() ?: run {
            viewModelScope.launch { _events.emit("Unable to stop recording") }
            return
        }

        val suggestedName = buildSuggestedFilename()
        _uiState.update {
            it.copy(
                saveDialog = SaveDialogState(
                    tempFilePath = tempFile.absolutePath,
                    filenameInput = suggestedName,
                    startTimeEpoch = current.startTime.toEpochSecond(),
                    startTimeOffset = current.startTime.offset.id,
                    durationSeconds = current.durationSeconds,
                    latitude = current.location?.latitude,
                    longitude = current.location?.longitude,
                    locationAccuracyM = current.location?.accuracyMeters
                )
            )
        }
    }

    fun onSaveFilenameChanged(name: String) {
        _uiState.update { state ->
            state.copy(saveDialog = state.saveDialog?.copy(filenameInput = name))
        }
    }

    fun onSaveDialogDismissed() {
        _uiState.update { it.copy(saveDialog = null) }
    }

    fun onSaveConfirmed() {
        val dialog = _uiState.value.saveDialog ?: return
        val tempFile = File(dialog.tempFilePath)
        val context = getApplication<Application>()

        val desiredName = if (dialog.filenameInput.endsWith(".m4a")) {
            dialog.filenameInput
        } else {
            "${dialog.filenameInput}.m4a"
        }

        // Reconstruct recording snapshot for MetadataWriter from the primitive fields in SaveDialogState
        val startTime = ZonedDateTime.ofEpochSecond(
            dialog.startTimeEpoch,
            0,
            ZoneOffset.of(dialog.startTimeOffset)
        )
        val location = if (dialog.latitude != null && dialog.longitude != null) {
            LocationFix(
                latitude = dialog.latitude,
                longitude = dialog.longitude,
                accuracyMeters = dialog.locationAccuracyM ?: 0f
            )
        } else null

        val recordingSnapshot = RecordingState.Recording(
            startTime = startTime,
            location = location,
            tempFile = tempFile,
            durationSeconds = dialog.durationSeconds
        )

        val metadataJson = MetadataWriter.buildJson(desiredName, recordingSnapshot, ZonedDateTime.now())

        _uiState.update { it.copy(saveDialog = null) }

        viewModelScope.launch {
            val uri = FileSaver.saveRecording(context, tempFile, desiredName, metadataJson)
            if (uri != null) {
                _events.emit("Saved $desiredName")
            } else {
                _events.emit("Failed to save recording")
            }
        }
    }

    private fun buildSuggestedFilename(): String {
        val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").format(ZonedDateTime.now())
        return "misc_$timestamp"
    }
}
