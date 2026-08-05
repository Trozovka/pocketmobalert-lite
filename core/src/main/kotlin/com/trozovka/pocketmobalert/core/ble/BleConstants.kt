package com.trozovka.pocketmobalert.core.ble

import java.util.UUID

/**
 * Shared between Crew-mode advertising and Watch-mode scanning. The service UUID is this app's
 * own private 128-bit identifier (not SIG-assigned) -- Watch mode filters scans to only this
 * UUID so it never reacts to unrelated BLE devices nearby (headphones, other beacons, etc).
 */
object BleConstants {
    val CREW_SERVICE_UUID: UUID = UUID.fromString("6f2c9e10-6b1d-4a3e-9c7a-6f6a2b7a9e01")

    /** Watch-mode devices advertise this UUID to each other only while they have a direct-detection
     * alarm active, so every other paired Watch device on the boat sounds too (non-negotiable #3) --
     * not a multi-hop mesh, single-hop broadcast only: a device relays its own direct detections,
     * never something it merely heard from another Watch device (keeps the protocol simple, avoids
     * bounce loops). */
    val WATCH_ALERT_SERVICE_UUID: UUID = UUID.fromString("6f2c9e11-6b1d-4a3e-9c7a-6f6a2b7a9e01")

    /** Advertising interval: favors faster Watch-mode detection over battery life, but BLE
     * advertising is inherently low-power regardless (non-negotiable #1's "genuinely lightweight"
     * requirement is about the foreground-service overhead around it, not this setting). */
    const val ADVERTISE_INTERVAL_MODE_BALANCED = 1 // AdvertiseSettings.ADVERTISE_MODE_BALANCED

    const val NOTIFICATION_CHANNEL_ID = "pocketmobalert_ble_channel"
}
