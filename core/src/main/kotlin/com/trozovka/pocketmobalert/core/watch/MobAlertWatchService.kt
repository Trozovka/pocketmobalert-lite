package com.trozovka.pocketmobalert.core.watch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.NotificationCompat
import com.trozovka.pocketmobalert.core.MainActivity
import com.trozovka.pocketmobalert.core.R
import com.trozovka.pocketmobalert.core.ble.BleConstants
import com.trozovka.pocketmobalert.core.ble.BlePermissions
import com.trozovka.pocketmobalert.core.ble.CrewDeviceIdentity
import com.trozovka.pocketmobalert.core.data.AlertLogEntity
import com.trozovka.pocketmobalert.core.data.PocketMobAlertDatabase
import com.trozovka.pocketmobalert.core.entitlement.EntitlementManager
import com.trozovka.pocketmobalert.core.entitlement.EntitlementManagerHolder
import com.trozovka.pocketmobalert.core.opencpn.OpenCpnSettings
import com.trozovka.pocketmobalert.core.opencpn.WplSender
import com.trozovka.toolkit.reliability.WakeLockController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class CrewSighting(val deviceIdHex: String, val rssi: Int, val lastSeenMillis: Long)

/**
 * Watch mode: scans continuously for any Crew-mode beacon (paired or not -- pairing UI reads
 * [sightings] to offer unpaired ones as "add crew" candidates), evaluates each currently-paired
 * device's [DeviceAlarmState] via [SeparationMonitor] every second, and owns the full alarm
 * behavior once a real separation is confirmed:
 *
 * - Sounds + vibrates at max volume on this device ([AlarmSoundController]).
 * - Relays the alarm to every other paired Watch device on the boat by advertising
 *   [BleConstants.WATCH_ALERT_SERVICE_UUID] (non-negotiable #3 -- no single point of failure).
 *   A device relays only its own direct detections, never something it merely heard from another
 *   Watch device (single-hop broadcast, not a multi-hop mesh).
 * - Captures a last-known GPS position and logs the event locally the moment a device first
 *   enters Alarming (edge-triggered, not once per evaluation tick).
 *
 * Detection and the core alarm are pure local BLE, no internet needed or used (non-negotiable
 * #1) -- the one optional exception is the Pro-only OpenCPN $WPL broadcast (local network only,
 * never internet, never blocks or gates the alarm itself either way).
 */
class MobAlertWatchService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var evaluationJob: Job? = null
    private lateinit var wakeLock: WakeLockController
    private lateinit var pairedCrewStore: PairedCrewStore
    private lateinit var alarmSound: AlarmSoundController
    private lateinit var ownDeviceIdHex: String
    private lateinit var entitlementManager: EntitlementManager
    private lateinit var openCpnSettings: OpenCpnSettings
    private var scanner: BluetoothLeScanner? = null
    private var relayAdvertiser: BluetoothLeAdvertiser? = null
    private var isRelayAdvertising = false

    private val separationMonitor = SeparationMonitor()
    private val lastSeenMillis = mutableMapOf<String, Long>()
    private val loggedDeviceIds = mutableSetOf<String>()

    @Volatile
    private var relayedAlertLatched = false

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val record = result.scanRecord ?: return
            val now = System.currentTimeMillis()

            record.getServiceData(ParcelUuid(BleConstants.CREW_SERVICE_UUID))?.let { data ->
                val deviceId = data.joinToString("") { "%02x".format(it) }
                lastSeenMillis[deviceId] = now
                _sightings.value = _sightings.value + (deviceId to CrewSighting(deviceId, result.rssi, now))
            }

            record.getServiceData(ParcelUuid(BleConstants.WATCH_ALERT_SERVICE_UUID))?.let { data ->
                val sourceId = data.joinToString("") { "%02x".format(it) }
                if (sourceId != ownDeviceIdHex) {
                    relayedAlertLatched = true
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE scan failed to start, errorCode=$errorCode")
            _scanError.value = scanErrorCodeToMessage(errorCode)
        }
    }

    private val relayAdvertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            // Graceful degradation (non-negotiable #2): this device just can't relay outward --
            // it still sounds its own alarm locally and can still receive relays from others.
            Log.w(TAG, "Watch-alert relay advertising failed to start, errorCode=$errorCode")
            isRelayAdvertising = false
        }
    }

    override fun onCreate() {
        super.onCreate()
        wakeLock = WakeLockController(applicationContext, "$packageName:WatchWakeLock")
        pairedCrewStore = PairedCrewStore(applicationContext)
        alarmSound = AlarmSoundController(applicationContext)
        ownDeviceIdHex = CrewDeviceIdentity.getOrCreateHex(applicationContext)
        entitlementManager = (application as EntitlementManagerHolder).entitlementManager
        openCpnSettings = OpenCpnSettings(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ACKNOWLEDGE -> {
                intent.getStringExtra(EXTRA_DEVICE_ID)?.let {
                    separationMonitor.acknowledge(it)
                    // Without this, only the first-ever Alarming edge for a given paired device
                    // gets written to the alert log for the lifetime of this service instance --
                    // the sound/vibrate/relay still correctly re-fire on every real re-separation,
                    // but silently stop being logged after the first one. Real bug, caught by
                    // directly inspecting the on-device DB after a real two-phone soak test: the
                    // sound was audibly confirmed firing repeatedly, but the log stayed frozen.
                    loggedDeviceIds -= it
                }
                return START_STICKY
            }
            ACTION_ACKNOWLEDGE_RELAY -> {
                relayedAlertLatched = false
                return START_STICKY
            }
        }
        startForegroundWithNotification()
        startScanning()
        startEvaluationLoop()
        return START_STICKY
    }

    private fun startScanning() {
        if (!BlePermissions.hasScanPermission(this) || !BlePermissions.hasConnectPermission(this)) {
            _scanError.value = "Bluetooth permission not granted"
            stopSelf()
            return
        }

        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            _scanError.value = "Bluetooth is turned off"
            stopSelf()
            return
        }

        scanner = adapter.bluetoothLeScanner
        relayAdvertiser = if (adapter.isMultipleAdvertisementSupported) adapter.bluetoothLeAdvertiser else null
        if (scanner == null) {
            _scanError.value = "Bluetooth scanner unavailable"
            stopSelf()
            return
        }

        wakeLock.acquire()

        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(BleConstants.CREW_SERVICE_UUID)).build(),
            ScanFilter.Builder().setServiceUuid(ParcelUuid(BleConstants.WATCH_ALERT_SERVICE_UUID)).build(),
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner?.startScan(filters, settings, scanCallback)
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing runtime permission for startScan", e)
            _scanError.value = "Bluetooth permission not granted"
            wakeLock.release()
            stopSelf()
        }
    }

    private fun startEvaluationLoop() {
        evaluationJob = scope.launch {
            while (isActive) {
                val pairedDevices = pairedCrewStore.getAll()
                val pairedIds = pairedDevices.map { it.deviceIdHex }.toSet()
                val newStates = separationMonitor.evaluate(pairedIds, lastSeenMillis)
                _alarmStates.value = newStates

                for (device in pairedDevices) {
                    if (newStates[device.deviceIdHex] == DeviceAlarmState.Alarming &&
                        device.deviceIdHex !in loggedDeviceIds
                    ) {
                        loggedDeviceIds += device.deviceIdHex
                        logAlarm(device.deviceIdHex, device.label)
                    }
                }

                val hasDirectAlarm = newStates.values.any { it == DeviceAlarmState.Alarming }
                updateRelayAdvertising(active = hasDirectAlarm)

                _relayedAlertActive.value = relayedAlertLatched
                if (hasDirectAlarm || relayedAlertLatched) alarmSound.start() else alarmSound.stop()

                delay(EVALUATION_INTERVAL_MILLIS)
            }
        }
    }

    private suspend fun logAlarm(deviceIdHex: String, label: String) {
        val position = captureLastKnownLocation(applicationContext)

        if (position != null && entitlementManager.isOpenCpnIntegrationUnlocked() && openCpnSettings.isEnabled) {
            WplSender.send(position.first, position.second, "MOB", openCpnSettings.port)
        }

        val dao = PocketMobAlertDatabase.getInstance(applicationContext).alertLogDao()
        dao.insert(
            AlertLogEntity(
                crewDeviceIdHex = deviceIdHex,
                crewLabel = label,
                timestampMillis = System.currentTimeMillis(),
                latitude = position?.first,
                longitude = position?.second,
            ),
        )
    }

    private fun updateRelayAdvertising(active: Boolean) {
        if (active == isRelayAdvertising) return
        val advertiser = relayAdvertiser ?: return
        if (!BlePermissions.hasAdvertisePermission(this) || !BlePermissions.hasConnectPermission(this)) return

        try {
            if (active) {
                val settings = AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                    .setConnectable(true)
                    .build()
                val data = AdvertiseData.Builder().setIncludeDeviceName(false)
                    .addServiceUuid(ParcelUuid(BleConstants.WATCH_ALERT_SERVICE_UUID))
                    .build()
                val ownIdBytes = ByteArray(ownDeviceIdHex.length / 2) { i ->
                    ownDeviceIdHex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                }
                val scanResponse = AdvertiseData.Builder().setIncludeDeviceName(false)
                    .addServiceData(ParcelUuid(BleConstants.WATCH_ALERT_SERVICE_UUID), ownIdBytes)
                    .build()
                advertiser.startAdvertising(settings, data, scanResponse, relayAdvertiseCallback)
                isRelayAdvertising = true
            } else {
                advertiser.stopAdvertising(relayAdvertiseCallback)
                isRelayAdvertising = false
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing runtime permission for relay advertising", e)
        }
    }

    private fun stopScanning() {
        try {
            if (BlePermissions.hasScanPermission(this)) {
                scanner?.stopScan(scanCallback)
            }
            if (isRelayAdvertising && BlePermissions.hasAdvertisePermission(this)) {
                relayAdvertiser?.stopAdvertising(relayAdvertiseCallback)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing runtime permission for stopScan/stopAdvertising", e)
        }
        scanner = null
        relayAdvertiser = null
        isRelayAdvertising = false
    }

    override fun onDestroy() {
        evaluationJob?.cancel()
        scope.cancel()
        stopScanning()
        alarmSound.stop()
        wakeLock.release()
        loggedDeviceIds.clear()
        relayedAlertLatched = false
        _sightings.value = emptyMap()
        _alarmStates.value = emptyMap()
        _relayedAlertActive.value = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundWithNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                BleConstants.NOTIFICATION_CHANNEL_ID,
                "Watch Mode",
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        // Tapping the notification must reopen the app -- a foreground service correctly
        // survives the app being swiped away from Recents (a safety alarm must not silently stop
        // just because of an accidental swipe), but that means the notification is the only way
        // back in to see status or acknowledge an alarm once the UI is gone.
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification: Notification = NotificationCompat.Builder(this, BleConstants.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("PocketMOBAlert -- Watch mode active")
            .setContentText("Monitoring paired crew devices -- no network involved")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun scanErrorCodeToMessage(errorCode: Int): String = when (errorCode) {
        ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "Scan already started"
        ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "Bluetooth scan registration failed"
        ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "This device doesn't support BLE scanning"
        ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "Internal Bluetooth error"
        else -> "Unknown Bluetooth scan error ($errorCode)"
    }

    companion object {
        private const val TAG = "MobAlertWatchService"
        private const val NOTIFICATION_ID = 3
        private const val EVALUATION_INTERVAL_MILLIS = 1_000L
        const val ACTION_ACKNOWLEDGE = "com.trozovka.pocketmobalert.core.action.ACKNOWLEDGE"
        const val ACTION_ACKNOWLEDGE_RELAY = "com.trozovka.pocketmobalert.core.action.ACKNOWLEDGE_RELAY"
        const val EXTRA_DEVICE_ID = "extra_device_id"

        /** Every Crew-mode beacon seen this session, paired or not -- the pairing UI offers
         * unpaired sightings as "add crew" candidates. */
        private val _sightings = MutableStateFlow<Map<String, CrewSighting>>(emptyMap())
        val sightings: StateFlow<Map<String, CrewSighting>> = _sightings.asStateFlow()

        private val _alarmStates = MutableStateFlow<Map<String, DeviceAlarmState>>(emptyMap())
        val alarmStates: StateFlow<Map<String, DeviceAlarmState>> = _alarmStates.asStateFlow()

        /** True if this device is currently sounding because of an alert relayed from another
         * Watch device (not its own direct detection). Separately acknowledgeable. */
        private val _relayedAlertActive = MutableStateFlow(false)
        val relayedAlertActive: StateFlow<Boolean> = _relayedAlertActive.asStateFlow()

        private val _scanError = MutableStateFlow<String?>(null)
        val scanError: StateFlow<String?> = _scanError.asStateFlow()
    }
}
