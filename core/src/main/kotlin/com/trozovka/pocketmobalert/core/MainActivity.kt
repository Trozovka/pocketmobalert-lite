package com.trozovka.pocketmobalert.core

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.trozovka.pocketmobalert.core.beacon.MobAlertBeaconService
import com.trozovka.pocketmobalert.core.ble.BlePermissionFlow
import com.trozovka.pocketmobalert.core.data.AlertLogEntity
import com.trozovka.pocketmobalert.core.data.PocketMobAlertDatabase
import com.trozovka.pocketmobalert.core.entitlement.EntitlementManager
import com.trozovka.pocketmobalert.core.entitlement.EntitlementManagerHolder
import com.trozovka.pocketmobalert.core.watch.DeviceAlarmState
import com.trozovka.pocketmobalert.core.watch.MobAlertWatchService
import com.trozovka.pocketmobalert.core.watch.PairedCrewStore
import com.trozovka.toolkit.reliability.LocationReliabilityPermissionFlow
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {

    private lateinit var blePermissionFlow: BlePermissionFlow
    private lateinit var locationPermissionFlow: LocationReliabilityPermissionFlow
    private var pendingServiceToStart: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        locationPermissionFlow = LocationReliabilityPermissionFlow(
            activity = this,
            onReady = { pendingServiceToStart?.invoke() },
            onDenied = { },
        )
        blePermissionFlow = BlePermissionFlow(
            activity = this,
            onReady = { locationPermissionFlow.begin() },
            onDenied = { },
        )

        val entitlementManager = (application as EntitlementManagerHolder).entitlementManager
        val pairedCrewStore = PairedCrewStore(applicationContext)

        setContent {
            MaterialTheme {
                Scaffold { padding ->
                    Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                        AppTabs(
                            onStartCrewMode = {
                                pendingServiceToStart = { startService(Intent(this@MainActivity, MobAlertBeaconService::class.java)) }
                                blePermissionFlow.begin()
                            },
                            onStopCrewMode = { stopService(Intent(this@MainActivity, MobAlertBeaconService::class.java)) },
                            onStartWatchMode = {
                                pendingServiceToStart = { startService(Intent(this@MainActivity, MobAlertWatchService::class.java)) }
                                blePermissionFlow.begin()
                            },
                            onStopWatchMode = { stopService(Intent(this@MainActivity, MobAlertWatchService::class.java)) },
                            onAcknowledge = { deviceId ->
                                startService(
                                    Intent(this@MainActivity, MobAlertWatchService::class.java)
                                        .setAction(MobAlertWatchService.ACTION_ACKNOWLEDGE)
                                        .putExtra(MobAlertWatchService.EXTRA_DEVICE_ID, deviceId),
                                )
                            },
                            onAcknowledgeRelay = {
                                startService(
                                    Intent(this@MainActivity, MobAlertWatchService::class.java)
                                        .setAction(MobAlertWatchService.ACTION_ACKNOWLEDGE_RELAY),
                                )
                            },
                            pairedCrewStore = pairedCrewStore,
                            entitlementManager = entitlementManager,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppTabs(
    onStartCrewMode: () -> Unit,
    onStopCrewMode: () -> Unit,
    onStartWatchMode: () -> Unit,
    onStopWatchMode: () -> Unit,
    onAcknowledge: (String) -> Unit,
    onAcknowledgeRelay: () -> Unit,
    pairedCrewStore: PairedCrewStore,
    entitlementManager: EntitlementManager,
) {
    var selectedTab by remember { mutableIntStateOf(0) }

    Column {
        TabRow(selectedTabIndex = selectedTab) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Crew Mode") })
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Watch Mode") })
        }
        when (selectedTab) {
            0 -> CrewModeScreen(onStartCrewMode, onStopCrewMode)
            1 -> WatchModeScreen(
                onStartWatchMode, onStopWatchMode, onAcknowledge, onAcknowledgeRelay,
                pairedCrewStore, entitlementManager,
            )
        }
    }
}

@Composable
private fun CrewModeScreen(onStartCrewMode: () -> Unit, onStopCrewMode: () -> Unit) {
    val status by MobAlertBeaconService.status.collectAsState()
    val deviceId by MobAlertBeaconService.deviceIdHex.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Crew mode: $status")
        deviceId?.let { Text("Device ID: $it") }
        Button(onClick = onStartCrewMode) { Text("Start Crew Mode") }
        Button(onClick = onStopCrewMode) { Text("Stop Crew Mode") }
    }
}

@Composable
private fun WatchModeScreen(
    onStartWatchMode: () -> Unit,
    onStopWatchMode: () -> Unit,
    onAcknowledge: (String) -> Unit,
    onAcknowledgeRelay: () -> Unit,
    pairedCrewStore: PairedCrewStore,
    entitlementManager: EntitlementManager,
) {
    val sightings by MobAlertWatchService.sightings.collectAsState()
    val alarmStates by MobAlertWatchService.alarmStates.collectAsState()
    val relayedAlertActive by MobAlertWatchService.relayedAlertActive.collectAsState()
    val scanError by MobAlertWatchService.scanError.collectAsState()

    // alarmStates ticks every ~1s while Watch mode runs, which doubles as this screen's refresh
    // cadence for the paired list (SharedPreferences-backed, not itself a reactive flow) and the
    // alert log (Room, not a reactive flow either).
    var pairedDevices by remember { mutableStateOf(pairedCrewStore.getAll()) }
    var maxCrewDevices by remember { mutableStateOf<Int?>(null) }
    var historyUnlocked by remember { mutableStateOf(false) }
    var alertLog by remember { mutableStateOf<List<AlertLogEntity>>(emptyList()) }
    val context = LocalContext.current
    LaunchedEffect(alarmStates) {
        pairedDevices = pairedCrewStore.getAll()
        maxCrewDevices = entitlementManager.maxPairedCrewDevices()
        historyUnlocked = entitlementManager.isHistoryAndExportUnlocked()
        val dao = PocketMobAlertDatabase.getInstance(context).alertLogDao()
        alertLog = if (historyUnlocked) dao.getAll() else listOfNotNull(dao.getMostRecent())
    }

    val pairedIds = pairedDevices.map { it.deviceIdHex }.toSet()
    val unpairedSightings = sightings.filterKeys { it !in pairedIds }
    val atCapacity = maxCrewDevices?.let { pairedDevices.size >= it } ?: false

    Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = onStartWatchMode) { Text("Start Watch Mode") }
        Button(onClick = onStopWatchMode) { Text("Stop Watch Mode") }
        scanError?.let { Text("Error: $it") }

        if (relayedAlertActive) {
            Text("ALARM relayed from another Watch device -- a paired crew member may be overboard.")
            Button(onClick = onAcknowledgeRelay) { Text("Acknowledge Relayed Alarm") }
        }

        Text("Paired crew (${pairedDevices.size}${maxCrewDevices?.let { "/$it" } ?: ""}):")
        LazyColumn {
            items(pairedDevices) { device ->
                val state = alarmStates[device.deviceIdHex] ?: DeviceAlarmState.Present
                PairedDeviceRow(
                    label = device.label,
                    state = state,
                    onRemove = {
                        pairedCrewStore.remove(device.deviceIdHex)
                        pairedDevices = pairedCrewStore.getAll()
                    },
                    onAcknowledge = { onAcknowledge(device.deviceIdHex) },
                )
            }
        }

        // Discovery/pairing UI only matters before you've paired your person -- this app is for
        // a specific small number of people watching each other's backs (typically just one),
        // not a roster to keep browsing once you're already set up.
        var pendingNames by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
        if (pairedDevices.isEmpty()) {
            Text("Nearby crew beacons not yet paired:")
            LazyColumn {
                items(unpairedSightings.values.toList()) { sighting ->
                    Column {
                        Text("${sighting.deviceIdHex}  (${sighting.rssi} dBm)")
                        OutlinedTextField(
                            value = pendingNames[sighting.deviceIdHex] ?: "",
                            onValueChange = { pendingNames = pendingNames + (sighting.deviceIdHex to it) },
                            label = { Text("Name (e.g. Chief Engineer)") },
                        )
                        val typedName = pendingNames[sighting.deviceIdHex]?.trim().orEmpty()
                        Button(
                            enabled = !atCapacity && typedName.isNotEmpty(),
                            onClick = {
                                pairedCrewStore.add(sighting.deviceIdHex, typedName)
                                pairedDevices = pairedCrewStore.getAll()
                                pendingNames = pendingNames - sighting.deviceIdHex
                            },
                        ) { Text("Add Crew") }
                    }
                }
            }
        }

        Text(if (historyUnlocked) "Alert log:" else "Most recent alert (Pro unlocks full history):")
        LazyColumn {
            items(alertLog) { entry ->
                val timestamp = DateFormat.getDateTimeInstance().format(Date(entry.timestampMillis))
                val position = if (entry.latitude != null && entry.longitude != null) {
                    "%.5f, %.5f".format(entry.latitude, entry.longitude)
                } else {
                    "position unavailable"
                }
                Text("${entry.crewLabel} -- $timestamp -- $position")
            }
        }
    }
}

@Composable
private fun PairedDeviceRow(
    label: String,
    state: DeviceAlarmState,
    onRemove: () -> Unit,
    onAcknowledge: () -> Unit,
) {
    Column {
        val stateText = when (state) {
            DeviceAlarmState.Present -> "Present"
            DeviceAlarmState.PossibleSeparation -> "Possible separation -- confirming..."
            DeviceAlarmState.Alarming -> "ALARM -- crew member may be overboard"
        }
        Text("$label: $stateText")
        if (state == DeviceAlarmState.Alarming) {
            Button(onClick = onAcknowledge) { Text("Acknowledge Alarm") }
        }
        Button(onClick = onRemove) { Text("Remove") }
    }
}
