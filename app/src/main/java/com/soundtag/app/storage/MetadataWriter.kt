package com.soundtag.app.storage

import android.os.Build
import com.soundtag.app.BuildConfig
import com.soundtag.app.recording.RecordingState
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

object MetadataWriter {
    private val json = Json {
        encodeDefaults = true
        prettyPrint = true
    }

    fun buildJson(filename: String, state: RecordingState.Recording, stopTime: ZonedDateTime): String {
        val metadata = RecordingMetadata(
            label = extractLabel(filename),
            filename = filename,
            latitude = state.location?.latitude,
            longitude = state.location?.longitude,
            locationAccuracyM = state.location?.accuracyMeters,
            startedAtLocal = state.startTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            startedAtUtc = state.startTime.withZoneSameInstant(ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            durationSeconds = state.durationSeconds,
            sampleRateHz = 44100,
            channels = 1,
            encoding = "AAC",
            deviceModel = Build.MODEL,
            androidVersion = Build.VERSION.RELEASE,
            appVersion = BuildConfig.VERSION_NAME
        )
        return json.encodeToString(metadata)
    }

    private fun extractLabel(filename: String): String {
        return filename
            .substringBefore('_')
            .replace(Regex("[0-9]"), "")
            .lowercase()
            .ifEmpty { "misc" }
    }
}

@Serializable
private data class RecordingMetadata(
    val label: String,
    val filename: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    @SerialName("location_accuracy_m") val locationAccuracyM: Float? = null,
    @SerialName("started_at_local") val startedAtLocal: String,
    @SerialName("started_at_utc") val startedAtUtc: String,
    @SerialName("duration_seconds") val durationSeconds: Long,
    @SerialName("sample_rate_hz") val sampleRateHz: Int,
    val channels: Int,
    val encoding: String,
    @SerialName("device_model") val deviceModel: String,
    @SerialName("android_version") val androidVersion: String,
    @SerialName("app_version") val appVersion: String
)
