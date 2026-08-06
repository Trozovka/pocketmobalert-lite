package com.trozovka.pocketmobalert.core.entitlement

/**
 * Free/Pro boundary (per the agreed hybrid model): the core alarm loop is never gated -- only
 * paired-crew capacity, alert history/export, and the OpenCPN bonus integration differ.
 * [FreeEntitlementManager] implements the permanent free tier; the Pro app supplies its own
 * implementation that starts in a 30-day fully-unlocked trial and falls back to these same
 * values if the trial lapses without a valid license.
 */
interface EntitlementManager {
    /** Null means unlimited. */
    suspend fun maxPairedCrewDevices(): Int?

    suspend fun isHistoryAndExportUnlocked(): Boolean

    suspend fun isOpenCpnIntegrationUnlocked(): Boolean

    /** Null means this tier has no license concept at all (Free) -- the UI hides the license
     * section entirely rather than showing an empty/irrelevant one. */
    suspend fun licenseStatusMessage(): String?

    /** The currently-entered license key, if any (for prefilling the UI field). */
    fun getLicenseKeyInput(): String = ""

    /** Stores [key] and immediately attempts to verify it. No-op for tiers with no license
     * concept. */
    suspend fun setLicenseKeyAndVerify(key: String) {}
}

class FreeEntitlementManager : EntitlementManager {
    override suspend fun maxPairedCrewDevices(): Int? = FREE_CREW_DEVICE_CAP

    override suspend fun isHistoryAndExportUnlocked(): Boolean = false

    override suspend fun isOpenCpnIntegrationUnlocked(): Boolean = false

    override suspend fun licenseStatusMessage(): String? = null

    companion object {
        const val FREE_CREW_DEVICE_CAP = 2
    }
}
