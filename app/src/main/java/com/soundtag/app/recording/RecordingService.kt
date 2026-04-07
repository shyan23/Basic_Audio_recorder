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

    // Instance-level state — not static. Avoids stale state leaking across service restarts.
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

        // startForeground must be called within 5 seconds of service start — do it first.
        startForeground(
            NotificationHelper.NOTIFICATION_ID,
            NotificationHelper.buildRecordingNotification(this, "00:00")
        )

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
            // stop() throws if recording never started properly; safe to ignore
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

                val notification = NotificationHelper.buildRecordingNotification(
                    this@RecordingService,
                    formatDuration(elapsed)
                )
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
