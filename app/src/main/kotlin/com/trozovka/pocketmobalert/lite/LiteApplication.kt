package com.trozovka.pocketmobalert.lite

import android.app.Application
import com.trozovka.pocketmobalert.core.entitlement.EntitlementManager
import com.trozovka.pocketmobalert.core.entitlement.EntitlementManagerHolder
import com.trozovka.pocketmobalert.core.entitlement.FreeEntitlementManager

class LiteApplication : Application(), EntitlementManagerHolder {
    override val entitlementManager: EntitlementManager by lazy { FreeEntitlementManager() }
}
