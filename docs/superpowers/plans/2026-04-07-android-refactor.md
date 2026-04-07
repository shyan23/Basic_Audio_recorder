# Android Refactor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refactor the SoundTag Android codebase from junior-quality to production-quality — fixing bugs, eliminating duplication, adding a ViewModel, and separating concerns.

**Architecture:** ViewModel owns all ephemeral UI state (permissions, save dialog, service connection) and survives rotation. `RecordingService` exposes instance-level `StateFlow` instead of a companion-object static. `MainActivity` is reduced to lifecycle glue only. All `@Composable` functions live in `MainScreen.kt`.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), ViewModel + StateFlow, LifecycleService, MediaRecorder, FusedLocationProvider, kotlinx.serialization

---

## Task 1: Fix build.gradle.kts

**Files:**
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Enable buildConfig and release minification, update stale deps**

Replace the entire `app/build.gradle.kts` with:

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.soundtag.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.soundtag.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true   // was missing — BuildConfig.VERSION_NAME used in MetadataWriter
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.4"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-service:2.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")
    implementation("androidx.compose.ui:ui:1.6.8")
    implementation("androidx.compose.material3:material3:1.2.1")
    implementation("androidx.compose.ui:ui-tooling-preview:1.6.8")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
    implementation("androidx.core:core-splashscreen:1.0.1")

    debugImplementation("androidx.compose.ui:ui-tooling:1.6.8")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.6.8")
}
```

- [ ] **Step 2: Verify project syncs**

In Android Studio: File → Sync Project with Gradle Files. Expected: BUILD SUCCESSFUL, no "Unresolved reference: BuildConfig" errors.

- [ ] **Step 3: Commit**

```bash
git add app/build.gradle.kts
git commit -m "build: enable buildConfig, release minification, update deps"
```

---

## Task 2: Create DurationFormatter utility

**Files:**
- Create: `app/src/main/java/com/soundtag/app/util/DurationFormatter.kt`

`formatDuration()` is currently copy-pasted in both `MainActivity` and `RecordingService`. One canonical home.

- [ ] **Step 1: Create the file**

```kotlin
package com.soundtag.app.util

/**
 * Formats a duration in seconds as MM:SS.
 * Example: 154 → "02:34"
 */
fun formatDuration(seconds: Long): String {
    val minutes = seconds / 60
    val remainder = seconds % 60
    return "%02d:%02d".format(minutes, remainder)
}
```

- [ ] **Step 2: Delete the duplicate in RecordingService**

In `app/src/main/java/com/soundtag/app/recording/RecordingService.kt`, remove the private `formatDuration()` method (lines ~144-148) and add the import:

```kotlin
import com.soundtag.app.util.formatDuration
```

- [ ] **Step 3: Delete the duplicate in MainActivity**

In `app/src/main/java/com/soundtag/app/MainActivity.kt`, remove the private `formatDuration()` method (lines ~362-366). The import will be added when MainActivity is refactored in Task 6.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/soundtag/app/util/DurationFormatter.kt \
        app/src/main/java/com/soundtag/app/recording/RecordingService.kt \
        app/src/main/java/com/soundtag/app/MainActivity.kt
git commit -m "refactor: extract shared formatDuration utility, remove duplication"
```

---

## Task 3: Fix RecordingMetadata serialization

**Files:**
- Modify: `app/src/main/java/com/soundtag/app/storage/MetadataWriter.kt`

`RecordingMetadata` uses raw snake_case property names which violates Kotlin naming conventions. The correct approach: camelCase properties + `@SerialName` annotations so the JSON output is unchanged. Also remove dead code (`Build.MODEL ?: "unknown"` — `Build.MODEL` is never null).

- [ ] **Step 1: Rewrite MetadataWriter.kt**

```kotlin
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
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/soundtag/app/storage/MetadataWriter.kt
git commit -m "refactor: use @SerialName for JSON fields, idiomatic Kotlin property names"
```

---

## Task 4: Fix RecordingService

**Files:**
- Modify: `app/src/main/java/com/soundtag/app/recording/RecordingService.kt`

**Three bugs to fix:**
1. `StateFlow` lives on the companion object — state leaks across service restarts. Move to instance.
2. `stopForeground(true)` is deprecated since API 33. Use `stopForeground(STOP_FOREGROUND_REMOVE)`.
3. `ACTION_STOP_RECORDING` is defined but never reachable (stop is always called via bound service). Remove it to avoid dead code confusion.

- [ ] **Step 1: Rewrite RecordingService.kt**

```kotlin
package com.soundtag.app.recording

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.soundtag.app.location.LocationHelper
import com.soundtag.app.notifications.NotificationHelper
import com.soundtag.app.util.formatDuration
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.time.Duration
import java.time.ZonedDateTime

class RecordingService : LifecycleService() {

    companion object {
        const val ACTION_START_RECORDING = "com.soundtag.app.action.START_RECORDING"
    }

    // Instance-level state — not static. Avoids stale state across service restarts.
    private val _recordingState = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val recordingState: StateFlow<RecordingState> = _recordingState.asStateFlow()

    private val binder = LocalBinder()
    private var recorder: MediaRecorder? = null
    private var timerJob: Job? = null
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private lateinit var locationHelper: LocationHelper

    inner class LocalBinder : Binder() {
        fun getService(): RecordingService = this@RecordingService
    }

    override fun onCreate() {
        super.onCreate()
        locationHelper = LocationHelper(applicationContext)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START_RECORDING) startRecording()
        return START_STICKY
    }

    fun startRecording() {
        if (recorder != null) return

        // startForeground must be called within 5 seconds of service start — call it first.
        startForeground(NotificationHelper.NOTIFICATION_ID, NotificationHelper.buildRecordingNotification(this, "00:00"))

        if (!requestAudioFocus()) {
            _recordingState.value = RecordingState.Error("Could not acquire audio focus")
            stopSelfClean()
            return
        }

        lifecycleScope.launch {
            val location = try {
                locationHelper.getLocation()
            } catch (_: Exception) {
                null
            }

            val tempFile = File(cacheDir, "soundtag_${System.currentTimeMillis()}.m4a")

            recorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44100)
                setAudioChannels(1)
                setOutputFile(tempFile.absolutePath)
                setOnErrorListener { _, what, extra ->
                    onRecorderError("MediaRecorder error: what=$what extra=$extra")
                }
                prepare()
                start()
            }

            val startTime = ZonedDateTime.now()
            _recordingState.value = RecordingState.Recording(startTime, location, tempFile)
            startTimer(startTime)
        }
    }

    fun stopRecording(): File? {
        val current = _recordingState.value as? RecordingState.Recording ?: return null

        try {
            recorder?.stop()
        } catch (_: Exception) {
            // stop() throws if recording never started; safe to ignore
        }
        recorder?.release()
        recorder = null
        timerJob?.cancel()
        timerJob = null
        abandonAudioFocus()

        _recordingState.value = RecordingState.Idle
        stopSelfClean()

        return current.tempFile
    }

    private fun stopSelfClean() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun startTimer(startTime: ZonedDateTime) {
        timerJob?.cancel()
        timerJob = lifecycleScope.launch {
            while (isActive) {
                val elapsed = Duration.between(startTime, ZonedDateTime.now()).seconds.coerceAtLeast(0)
                val current = _recordingState.value as? RecordingState.Recording ?: break
                _recordingState.value = current.copy(durationSeconds = elapsed)

                val notification = NotificationHelper.buildRecordingNotification(this@RecordingService, formatDuration(elapsed))
                getSystemService(NotificationManager::class.java)
                    ?.notify(NotificationHelper.NOTIFICATION_ID, notification)

                delay(1_000L)
            }
        }
    }

    private fun requestAudioFocus(): Boolean {
        val manager = audioManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener { focusChange ->
                    if (focusChange == AudioManager.AUDIOFOCUS_LOSS ||
                        focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                    ) {
                        stopRecording()
                    }
                }
                .build()
            audioFocusRequest = request
            manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            manager.requestAudioFocus(
                { focusChange ->
                    if (focusChange == AudioManager.AUDIOFOCUS_LOSS ||
                        focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                    ) {
                        stopRecording()
                    }
                },
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        val manager = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { manager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            manager.abandonAudioFocus(null)
        }
    }

    private fun onRecorderError(message: String) {
        _recordingState.value = RecordingState.Error(message)
        stopRecording()
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/soundtag/app/recording/RecordingService.kt
git commit -m "fix: instance-level StateFlow, stopForeground API compat, remove dead action"
```

---

## Task 5: Create UiState + MainViewModel

**Files:**
- Create: `app/src/main/java/com/soundtag/app/ui/UiState.kt`
- Create: `app/src/main/java/com/soundtag/app/ui/MainViewModel.kt`

The ViewModel holds all ephemeral screen state so rotation doesn't wipe it. It:
- Owns `permissionsGranted` 
- Connects to `RecordingService` and mirrors its `StateFlow` into its own `uiState`
- Owns `saveDialog` state (so the dialog survives a rotation mid-save)

- [ ] **Step 1: Create UiState.kt**

```kotlin
package com.soundtag.app.ui

import com.soundtag.app.recording.RecordingState

data class UiState(
    val recordingState: RecordingState = RecordingState.Idle,
    val permissionsGranted: Boolean = false,
    val saveDialog: SaveDialogState? = null
)

/**
 * State for the "save recording" dialog.
 * [tempFilePath] is a String (not File) so it survives ViewModel recreation.
 */
data class SaveDialogState(
    val tempFilePath: String,
    val filenameInput: String,
    val startTimeEpoch: Long,       // ZonedDateTime.toEpochSecond() of recording start
    val startTimeOffset: String,    // ZoneOffset id, e.g. "+06:00"
    val durationSeconds: Long,
    val latitude: Double?,
    val longitude: Double?,
    val locationAccuracyM: Float?
)
```

- [ ] **Step 2: Create MainViewModel.kt**

```kotlin
package com.soundtag.app.ui

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.soundtag.app.BuildConfig
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

    // One-shot events for snackbar messages
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

        // Reconstruct a minimal RecordingState.Recording for MetadataWriter
        val startTime = ZonedDateTime.ofEpochSecond(
            dialog.startTimeEpoch,
            0,
            ZoneOffset.of(dialog.startTimeOffset)
        )
        val recordingSnapshot = RecordingState.Recording(
            startTime = startTime,
            location = if (dialog.latitude != null && dialog.longitude != null) {
                com.soundtag.app.location.LocationFix(
                    latitude = dialog.latitude,
                    longitude = dialog.longitude,
                    accuracyMeters = dialog.locationAccuracyM ?: 0f
                )
            } else null,
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
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/soundtag/app/ui/UiState.kt \
        app/src/main/java/com/soundtag/app/ui/MainViewModel.kt
git commit -m "feat: add MainViewModel and UiState — survives rotation, owns all screen state"
```

---

## Task 6: Extract MainScreen composables

**Files:**
- Create: `app/src/main/java/com/soundtag/app/ui/MainScreen.kt`

All `@Composable` functions leave `MainActivity`. `MainActivity` will call `MainScreen(viewModel)` and nothing else.

- [ ] **Step 1: Create MainScreen.kt**

```kotlin
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
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/soundtag/app/ui/MainScreen.kt
git commit -m "refactor: extract all Composable functions to MainScreen.kt"
```

---

## Task 7: Slim down MainActivity

**Files:**
- Modify: `app/src/main/java/com/soundtag/app/MainActivity.kt`

After extracting the ViewModel and Composables, `MainActivity` should only do:
1. Request permissions (can't be done in ViewModel — needs Activity context)
2. Bind/unbind the service
3. Wire service connection → ViewModel
4. Call `setContent { MainScreen(...) }`

- [ ] **Step 1: Rewrite MainActivity.kt**

```kotlin
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
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.soundtag.app.recording.RecordingService
import com.soundtag.app.ui.MainScreen
import com.soundtag.app.ui.MainViewModel
import com.soundtag.app.ui.theme.SoundTagTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val recordingService = (service as RecordingService.LocalBinder).getService()
            viewModel.onServiceConnected(recordingService)
            serviceBound = true
        }

        override fun onServiceDisconnected(name: ComponentName) {
            viewModel.onServiceDisconnected()
            serviceBound = false
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        viewModel.onPermissionsResult(results.values.all { it })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialise permission state without prompting
        viewModel.onPermissionsResult(requiredPermissions.all { perm ->
            ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
        })

        setContent {
            SoundTagTheme {
                MainScreen(
                    viewModel = viewModel,
                    onRequestPermissions = { permissionLauncher.launch(requiredPermissions.toTypedArray()) },
                    onStartRecording = { startRecording() }
                )
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

    private fun startRecording() {
        val intent = Intent(this, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START_RECORDING
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, intent)
        } else {
            startService(intent)
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
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/soundtag/app/MainActivity.kt
git commit -m "refactor: slim MainActivity to lifecycle + ViewModel wiring only"
```

---

## Task 8: Final verification

- [ ] **Step 1: Build the project**

In Android Studio: Build → Make Project (or `./gradlew assembleDebug` from terminal).

Expected: BUILD SUCCESSFUL with 0 errors.

- [ ] **Step 2: Check for any leftover references to old static state**

```bash
grep -r "RecordingService.state" app/src/
```

Expected: no results. The old `RecordingService.state` companion property is gone; all access is via the bound service instance.

- [ ] **Step 3: Check for formatDuration duplication**

```bash
grep -rn "fun formatDuration" app/src/
```

Expected: exactly 1 result — `DurationFormatter.kt`.

- [ ] **Step 4: Smoke test on device/emulator**

- Launch app → grant permissions
- Tap Record → notification appears with "00:00" timer
- Wait 10 seconds → notification shows elapsed time updating
- Tap notification → app comes to foreground
- Tap Stop → Save dialog appears with suggested filename
- Change filename to `traffic_001` → tap Save
- Check `Music/SoundTag/` via Files app: `traffic_001.m4a` and `traffic_001.json` present
- Open JSON → verify `"label": "traffic"` is present and all fields populated

- [ ] **Step 5: Final commit**

```bash
git add -A
git commit -m "chore: final cleanup after refactor"
```
