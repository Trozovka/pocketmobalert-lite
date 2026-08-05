package com.trozovka.pocketmobalert.core.watch

/**
 * Turns "when was each paired crew device last seen" into an alarm state, with a two-phase
 * debounce so a real MOB event is distinguished from routine BLE dropout (walking below deck,
 * a body blocking line-of-sight for a moment).
 *
 * Phase 1 -- MISS_THRESHOLD_MILLIS (4s) of continuous silence: [DeviceAlarmState.PossibleSeparation].
 * A single missed advertisement or two doesn't mean anything on its own; BLE advertising every
 * ~1s means 4s of total silence is already several missed packets in a row, past what normal
 * signal jitter explains.
 *
 * Phase 2 -- another MISS_THRESHOLD_MILLIS (4s) with still no signal: [DeviceAlarmState.Alarming].
 * Total worst-case detection latency is 8 seconds from last real contact, under the <10s budget,
 * while phase 1 alone gives a few seconds for a device to reappear (walking back into range,
 * a brief obstruction) without ever reaching the alarm.
 *
 * Once Alarming, the state LATCHES -- it does not clear itself just because the signal comes
 * back (e.g. the phone floating and briefly reconnecting). A real MOB alarm must not silently
 * go away on its own; it requires an explicit [acknowledge] from the Watch operator. This is a
 * deliberate safety choice: auto-clearing on signal reacquisition risks the alarm going quiet
 * while the person is still in real danger.
 */
class SeparationMonitor(private val now: () -> Long = System::currentTimeMillis) {

    private val latchedAlarms = mutableSetOf<String>()

    /**
     * @param lastSeenMillis wall-clock time each paired device's BLE advertisement was last
     * received, keyed by device ID hex. A paired device with no entry yet (never seen this
     * session) is treated as [DeviceAlarmState.Alarming] immediately -- it never had a first
     * contact.
     */
    fun evaluate(pairedDeviceIds: Set<String>, lastSeenMillis: Map<String, Long>): Map<String, DeviceAlarmState> {
        val nowMillis = now()
        return pairedDeviceIds.associateWith { deviceId ->
            if (deviceId in latchedAlarms) {
                DeviceAlarmState.Alarming
            } else {
                val lastSeen = lastSeenMillis[deviceId]
                val state = if (lastSeen == null) {
                    DeviceAlarmState.Alarming
                } else {
                    classify(nowMillis - lastSeen)
                }
                if (state is DeviceAlarmState.Alarming) latchedAlarms += deviceId
                state
            }
        }
    }

    /** Clears the latch for [deviceId] so it re-evaluates fresh from its current signal state. */
    fun acknowledge(deviceId: String) {
        latchedAlarms -= deviceId
    }

    fun isLatched(deviceId: String): Boolean = deviceId in latchedAlarms

    private fun classify(millisSinceLastSeen: Long): DeviceAlarmState = when {
        millisSinceLastSeen < MISS_THRESHOLD_MILLIS -> DeviceAlarmState.Present
        millisSinceLastSeen < MISS_THRESHOLD_MILLIS + CONFIRMATION_WINDOW_MILLIS -> DeviceAlarmState.PossibleSeparation
        else -> DeviceAlarmState.Alarming
    }

    companion object {
        const val MISS_THRESHOLD_MILLIS = 4_000L
        const val CONFIRMATION_WINDOW_MILLIS = 4_000L
    }
}

sealed class DeviceAlarmState {
    data object Present : DeviceAlarmState()
    data object PossibleSeparation : DeviceAlarmState()
    data object Alarming : DeviceAlarmState()
}
