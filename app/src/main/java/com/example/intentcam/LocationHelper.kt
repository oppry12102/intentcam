package com.example.intentcam

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.location.Location
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Best-effort GPS + coarse reverse-geocoding helper. All calls are safe to make
 * without the permission granted (they simply return null).
 */
class LocationHelper(private val context: Context) {

    private val client = LocationServices.getFusedLocationProviderClient(context)

    /** Returns a short human-usable location string, or null if unavailable. */
    @SuppressLint("MissingPermission")
    suspend fun currentLocationText(): String? {
        val loc = currentLocation() ?: return null
        val coords = String.format(Locale.US, "%.6f,%.6f", loc.latitude, loc.longitude)
        val address = reverseGeocode(loc)
        return if (address != null) "$coords ($address)" else coords
    }

    @SuppressLint("MissingPermission")
    private suspend fun currentLocation(): Location? =
        suspendCancellableCoroutine { cont ->
            try {
                val cts = CancellationTokenSource()
                client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cts.token)
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resume(null) }
                cont.invokeOnCancellation { cts.cancel() }
            } catch (e: SecurityException) {
                cont.resume(null)
            } catch (e: Exception) {
                cont.resume(null)
            }
        }

    @Suppress("DEPRECATION")
    private fun reverseGeocode(loc: Location): String? = try {
        val list = Geocoder(context, Locale.getDefault())
            .getFromLocation(loc.latitude, loc.longitude, 1)
        list?.firstOrNull()?.let { a ->
            listOfNotNull(a.locality, a.subLocality, a.thoroughfare, a.featureName)
                .distinct()
                .joinToString(" ")
                .ifBlank { null }
        }
    } catch (e: Exception) {
        null
    }
}
