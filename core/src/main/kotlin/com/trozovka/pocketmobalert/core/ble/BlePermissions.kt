package com.trozovka.pocketmobalert.core.ble

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * BLUETOOTH_SCAN/ADVERTISE/CONNECT are Android 12+ runtime permissions, not needed on older
 * versions (BLE was permission-gated only by location before that). No prior app in this
 * portfolio used BLE, so this doesn't live in the shared toolkit yet -- if a future app needs
 * the same thing, this is the candidate to extract into :reliability alongside
 * LocationReliabilityPermissionFlow.
 */
object BlePermissions {
    fun hasScanPermission(context: Context): Boolean = hasPermission(context, Manifest.permission.BLUETOOTH_SCAN)

    fun hasAdvertisePermission(context: Context): Boolean =
        hasPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE)

    fun hasConnectPermission(context: Context): Boolean =
        hasPermission(context, Manifest.permission.BLUETOOTH_CONNECT)

    private fun hasPermission(context: Context, permission: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
}

/** Requests BLUETOOTH_SCAN + BLUETOOTH_ADVERTISE + BLUETOOTH_CONNECT together. No-op below
 * API 31, since Bluetooth was only location-gated before then. Must be constructed as an
 * Activity property (same timing requirement as LocationReliabilityPermissionFlow). */
class BlePermissionFlow(
    private val activity: ComponentActivity,
    private val onReady: () -> Unit,
    private val onDenied: () -> Unit = {},
) {
    private val requestPermissions = activity.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        if (results.values.all { it }) onReady() else onDenied()
    }

    fun begin() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            onReady()
            return
        }
        val needed = listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
        ).filter {
            ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isEmpty()) onReady() else requestPermissions.launch(needed.toTypedArray())
    }
}
