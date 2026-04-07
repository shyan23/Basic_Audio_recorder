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
    suspend fun saveRecording(
        context: Context,
        audioFile: File,
        desiredName: String,
        metadataJson: String
    ): Uri? = withContext(Dispatchers.IO) {
        val baseName = desiredName.removeSuffix(".m4a")
        val audioName = "$baseName.m4a"
        val jsonName = "$baseName.json"

        val audioUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveAudioQ(context, audioFile, audioName)
        } else {
            saveAudioLegacy(audioFile, audioName)
        }

        if (audioUri == null) {
            return@withContext null
        }

        val jsonSaved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveJsonQ(context, metadataJson, jsonName)
        } else {
            saveJsonLegacy(context, metadataJson, jsonName)
        }

        if (jsonSaved) {
            audioFile.delete()
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
        val musicDirectory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        val targetDirectory = File(musicDirectory, "SoundTag")
        if (!targetDirectory.exists()) {
            targetDirectory.mkdirs()
        }

        val destination = File(targetDirectory, displayName)
        source.copyTo(destination, overwrite = true)
        return Uri.fromFile(destination)
    }

    private fun saveJsonLegacy(context: Context, json: String, displayName: String): Boolean {
        val musicDirectory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        val targetDirectory = File(musicDirectory, "SoundTag")
        if (!targetDirectory.exists()) {
            targetDirectory.mkdirs()
        }

        val jsonFile = File(targetDirectory, displayName)
        jsonFile.writeText(json)
        return true
    }

    private fun copyToUri(source: File, uri: Uri, context: Context): Boolean {
        return context.contentResolver.openOutputStream(uri)?.use { output ->
            source.inputStream().use { input ->
                input.copyTo(output)
            }
            true
        } ?: false
    }
}
