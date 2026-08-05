package com.trozovka.pocketmobalert.core.entitlement

/** Implemented by each :app module's Application subclass (LiteApplication, ProApplication) so
 * :core code (MainActivity, ViewModels) can reach the active EntitlementManager without knowing
 * which flavor it's running in. */
interface EntitlementManagerHolder {
    val entitlementManager: EntitlementManager
}
