package com.soundtag.app.recording

import com.soundtag.app.location.LocationFix
import java.io.File
import java.time.ZonedDateTime

sealed class RecordingState {
    object Idle : RecordingState()

    data class Recording(
        val startTime: ZonedDateTime,
        val location: LocationFix?,
        val tempFile: File,
        val durationSeconds: Long = 0L
    ) : RecordingState()

    data class Error(val message: String) : RecordingState()
}
