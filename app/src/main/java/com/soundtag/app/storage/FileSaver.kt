package com.soundtag.app.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object FileSaver {

    /**
     * Saves [audioFile] (temp cache file) to Music/SoundTag/ with [desiredName],
     * then writes a [metadataJson] sidecar alongside it.
     *
     * Returns the saved audio [Uri] on success, null if the audio save failed.
     * The sidecar failure is non-fatal — audio is still returned.
     * The temp [audioFile] is always deleted after a successful audio save.
     */
    suspend fun saveRecording(
        context: Context,
        audioFile: File,
        desiredName: String,
        metadataJson: String
    ): Uri? = withContext(Dispatchers.IO) {
        val baseName  = desiredName.removeSuffix(".m4a")
        val audioName = "$baseName.m4a"
        val jsonName  = "$baseName.json"

        val audioUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveAudioQ(context, audioFile, audioName)
        } else {
            saveAudioLegacy(audioFile, audioName)
        }

        if (audioUri == null) return@withContext null

        // Temp file is no longer needed once audio is persisted
        audioFile.delete()

        // Sidecar failure is non-fatal: log it but still return the audio URI
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveJsonQ(context, metadataJson, jsonName)
        } else {
            saveJsonLegacy(metadataJson, jsonName)
        }

        audioUri
    }

    private fun saveAudioQ(context: Context, source: File, displayName: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/SoundTag")
        }
        val uri = context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null
        return if (copyToUri(source, uri, context)) uri else null
    }

    private fun saveJsonQ(context: Context, json: String, displayName: String): Boolean {
        val values = ContentValues().apply {
            put(MediaStore.Files.FileColumns.DISPLAY_NAME, displayName)
            put(MediaStore.Files.FileColumns.MIME_TYPE, "application/json")
            put(MediaStore.Files.FileColumns.RELATIVE_PATH, "Music/SoundTag")
        }
        val uri = context.contentResolver.insert(MediaStore.Files.getContentUri("external"), values)
            ?: return false
        return context.contentResolver.openOutputStream(uri)?.use { output ->
            output.write(json.toByteArray())
            true
        } ?: false
    }

    private fun saveAudioLegacy(source: File, displayName: String): Uri? {
        return try {
            val dir = soundTagDir() ?: return null
            val dest = File(dir, displayName)
            source.copyTo(dest, overwrite = true)
            android.net.Uri.fromFile(dest)
        } catch (_: Exception) {
            null
        }
    }

    private fun saveJsonLegacy(json: String, displayName: String): Boolean {
        return try {
            val dir = soundTagDir() ?: return false
            File(dir, displayName).writeText(json)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun soundTagDir(): File? {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            "SoundTag"
        )
        if (!dir.exists() && !dir.mkdirs()) return null
        return dir
    }

    private fun copyToUri(source: File, uri: Uri, context: Context): Boolean {
        return try {
            context.contentResolver.openOutputStream(uri)?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
                true
            } ?: false
        } catch (_: Exception) {
            false
        }
    }
}
