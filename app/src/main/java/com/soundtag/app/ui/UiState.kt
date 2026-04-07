package com.soundtag.app.ui

import com.soundtag.app.recording.RecordingState

data class UiState(
    val recordingState: RecordingState = RecordingState.Idle,
    val permissionsGranted: Boolean = false,
    val saveDialog: SaveDialogState? = null
)

/**
 * State for the "save recording" dialog.
 *
 * Stores primitives only (no File, no ZonedDateTime) so the ViewModel can hold this
 * across process death/recreation without issue.
 */
data class SaveDialogState(
    val tempFilePath: String,
    val filenameInput: String,
    val startTimeEpoch: Long,      // ZonedDateTime.toEpochSecond()
    val startTimeOffset: String,   // ZoneOffset id, e.g. "+06:00"
    val durationSeconds: Long,
    val latitude: Double?,
    val longitude: Double?,
    val locationAccuracyM: Float?
)
