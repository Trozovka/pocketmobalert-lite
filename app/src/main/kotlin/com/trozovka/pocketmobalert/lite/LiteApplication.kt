package com.trozovka.pocketmobalert.lite

import android.app.Application
import com.trozovka.pocketmobalert.core.entitlement.EntitlementManager
import com.trozovka.pocketmobalert.core.entitlement.FreeEntitlementManager

class LiteApplication : Application() {
    val entitlementManager: EntitlementManager by lazy { FreeEntitlementManager() }
}
