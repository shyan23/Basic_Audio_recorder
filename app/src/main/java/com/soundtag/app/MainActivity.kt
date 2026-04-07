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

    // Must be registered before Activity is STARTED — cannot go inside setContent
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        viewModel.onPermissionsResult(results.values.all { it })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Seed permission state without prompting on every launch
        viewModel.onPermissionsResult(
            requiredPermissions.all { perm ->
                ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
            }
        )

        setContent {
            SoundTagTheme {
                MainScreen(
                    viewModel = viewModel,
                    onRequestPermissions = { permissionLauncher.launch(requiredPermissions.toTypedArray()) },
                    onStartRecording = ::startRecording
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
            viewModel.onServiceDisconnected()
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
