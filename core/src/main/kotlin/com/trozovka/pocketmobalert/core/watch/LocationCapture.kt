package com.trozovka.pocketmobalert.core.watch

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** Last-known position at the moment an alarm fires -- favors speed over precision, since the
 * point is to record roughly where the crew member was lost, immediately, not to wait on a
 * fresh GPS fix while the alarm is already sounding. Returns null if location permission isn't
 * granted or no last-known fix exists yet. */
suspend fun captureLastKnownLocation(context: Context): Pair<Double, Double>? {
    val hasPermission = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED
    if (!hasPermission) return null

    return suspendCancellableCoroutine { cont ->
        val client = LocationServices.getFusedLocationProviderClient(context)
        try {
            client.lastLocation
                .addOnSuccessListener { location ->
                    if (cont.isActive) cont.resume(location?.let { it.latitude to it.longitude })
                }
                .addOnFailureListener {
                    if (cont.isActive) cont.resume(null)
                }
        } catch (e: SecurityException) {
            if (cont.isActive) cont.resume(null)
        }
    }
}
