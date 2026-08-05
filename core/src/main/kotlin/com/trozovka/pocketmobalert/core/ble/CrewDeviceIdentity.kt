package com.trozovka.pocketmobalert.core.ble

import android.content.Context
import java.security.SecureRandom

/**
 * A stable per-install identifier for this phone as a Crew-mode beacon, so Watch mode recognizes
 * the same phone consistently across a session (and across app restarts). Not a security
 * credential -- just enough entropy (8 random bytes) to tell paired crew phones apart in BLE
 * service data, generated once and persisted locally.
 */
object CrewDeviceIdentity {
    private const val PREFS_NAME = "pocketmobalert_identity"
    private const val KEY_DEVICE_ID = "crew_device_id"
    private const val ID_LENGTH_BYTES = 8

    fun getOrCreate(context: Context): ByteArray {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_DEVICE_ID, null)
        if (existing != null) return hexToBytes(existing)

        val newId = ByteArray(ID_LENGTH_BYTES).also { SecureRandom().nextBytes(it) }
        prefs.edit().putString(KEY_DEVICE_ID, bytesToHex(newId)).apply()
        return newId
    }

    fun getOrCreateHex(context: Context): String = bytesToHex(getOrCreate(context))

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}
