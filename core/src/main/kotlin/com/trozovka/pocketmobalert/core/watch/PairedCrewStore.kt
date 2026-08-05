package com.trozovka.pocketmobalert.core.watch

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class PairedCrewDevice(
    val deviceIdHex: String,
    val label: String,
    val pairedAtMillis: Long,
)

/**
 * The current voyage's paired-crew list, persisted locally (survives app restart, not shared
 * anywhere -- no network involved). A small, flat list, not relational data, so plain
 * SharedPreferences-backed JSON is simpler than standing up a Room table for it.
 */
class PairedCrewStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getAll(): List<PairedCrewDevice> {
        val raw = prefs.getString(KEY_DEVICES, null) ?: return emptyList()
        val array = JSONArray(raw)
        return (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            PairedCrewDevice(
                deviceIdHex = obj.getString("id"),
                label = obj.getString("label"),
                pairedAtMillis = obj.getLong("pairedAt"),
            )
        }
    }

    fun add(deviceIdHex: String, label: String) {
        val existing = getAll()
        if (existing.any { it.deviceIdHex == deviceIdHex }) return
        val updated = existing + PairedCrewDevice(deviceIdHex, label, System.currentTimeMillis())
        saveAll(updated)
    }

    fun remove(deviceIdHex: String) {
        saveAll(getAll().filterNot { it.deviceIdHex == deviceIdHex })
    }

    private fun saveAll(devices: List<PairedCrewDevice>) {
        val array = JSONArray()
        devices.forEach { device ->
            array.put(
                JSONObject().apply {
                    put("id", device.deviceIdHex)
                    put("label", device.label)
                    put("pairedAt", device.pairedAtMillis)
                },
            )
        }
        prefs.edit().putString(KEY_DEVICES, array.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "pocketmobalert_paired_crew"
        private const val KEY_DEVICES = "devices"
    }
}
