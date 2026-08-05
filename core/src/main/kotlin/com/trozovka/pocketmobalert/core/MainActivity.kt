package com.trozovka.pocketmobalert.core

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.trozovka.pocketmobalert.core.beacon.MobAlertBeaconService
import com.trozovka.pocketmobalert.core.ble.BlePermissionFlow
import com.trozovka.toolkit.reliability.LocationReliabilityPermissionFlow

class MainActivity : ComponentActivity() {

    private lateinit var blePermissionFlow: BlePermissionFlow
    private lateinit var locationPermissionFlow: LocationReliabilityPermissionFlow

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        locationPermissionFlow = LocationReliabilityPermissionFlow(
            activity = this,
            onReady = { startService(Intent(this, MobAlertBeaconService::class.java)) },
            onDenied = { },
        )
        blePermissionFlow = BlePermissionFlow(
            activity = this,
            onReady = { locationPermissionFlow.begin() },
            onDenied = { },
        )

        setContent {
            MaterialTheme {
                Scaffold { padding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                    ) {
                        HomeScreen(
                            onStartCrewMode = { blePermissionFlow.begin() },
                            onStopCrewMode = { stopService(Intent(this@MainActivity, MobAlertBeaconService::class.java)) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(onStartCrewMode: () -> Unit, onStopCrewMode: () -> Unit) {
    var running by remember { mutableStateOf(false) }
    val status by MobAlertBeaconService.status.collectAsState()
    val deviceId by MobAlertBeaconService.deviceIdHex.collectAsState()

    Text("PocketMOBAlert")
    Text("Crew mode: $status")
    deviceId?.let { Text("Device ID: $it") }

    Button(onClick = {
        running = true
        onStartCrewMode()
    }) {
        Text("Start Crew Mode")
    }
    Button(onClick = {
        running = false
        onStopCrewMode()
    }) {
        Text("Stop Crew Mode")
    }
}
