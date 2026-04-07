# SoundTag

A refactored Android codebase for a minimal background audio recorder with GPS metadata and JSON sidecar export.

## Structure

- `app/src/main/java/com/soundtag/app/MainActivity.kt` — Compose UI, permission flow, service control, save dialog.
- `app/src/main/java/com/soundtag/app/recording/RecordingService.kt` — foreground recording service with MediaRecorder, audio focus, and live notification.
- `app/src/main/java/com/soundtag/app/location/LocationHelper.kt` — one-shot GPS fix with timeout.
- `app/src/main/java/com/soundtag/app/storage/MetadataWriter.kt` — JSON metadata builder.
- `app/src/main/java/com/soundtag/app/storage/FileSaver.kt` — MediaStore + legacy save support.
- `app/src/main/java/com/soundtag/app/notifications/NotificationHelper.kt` — channel and notification builder.

## Build

Use Gradle to build the app:

```bash
./gradlew :app:assembleDebug
```

If you're missing the Gradle wrapper, use a local Gradle installation.

## Notes

- Target SDK 35, min SDK 26
- Uses Jetpack Compose and Material 3
- Handles Android 14 microphone foreground service requirements
- Saves recordings in `Music/SoundTag/` with matching `.m4a` and `.json` files
