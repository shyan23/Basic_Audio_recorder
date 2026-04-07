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
