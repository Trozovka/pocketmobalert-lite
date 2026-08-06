package com.trozovka.pocketmobalert.core.opencpn

import android.content.Context

/** Pro-only bonus feature toggle + the UDP port OpenCPN's own network-connection settings are
 * configured to listen on (no fixed standard port for NMEA-over-UDP, so it must be the
 * operator's own choice -- 10110 is offered as a common convention, not a guarantee). */
class OpenCpnSettings(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var isEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    var port: Int
        get() = prefs.getInt(KEY_PORT, DEFAULT_PORT)
        set(value) = prefs.edit().putInt(KEY_PORT, value).apply()

    companion object {
        private const val PREFS_NAME = "pocketmobalert_opencpn"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_PORT = "port"
        const val DEFAULT_PORT = 10110
    }
}
