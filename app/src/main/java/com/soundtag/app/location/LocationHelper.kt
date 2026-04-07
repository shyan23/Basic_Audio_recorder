package com.soundtag.app.location

import android.annotation.SuppressLint
import android.content.Context
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

class LocationHelper(context: Context) {
    private val fusedClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context.applicationContext)

    @SuppressLint("MissingPermission")
    suspend fun getLocation(): LocationFix? = withTimeoutOrNull(5_000L) {
        val lastLocation = fusedClient.lastLocation.await()
        val location = lastLocation ?: fusedClient
            .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .await()

        location?.let {
            LocationFix(
                latitude = it.latitude,
                longitude = it.longitude,
                accuracyMeters = it.accuracy
            )
        }
    }
}
