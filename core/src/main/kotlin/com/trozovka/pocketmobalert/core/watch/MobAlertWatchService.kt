package com.trozovka.pocketmobalert.core.watch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothManager
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
import com.trozovka.pocketmobalert.core.R
import com.trozovka.pocketmobalert.core.ble.BleConstants
import com.trozovka.pocketmobalert.core.ble.BlePermissions
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
 * [sightings] to offer unpaired ones as "add crew" candidates), and separately evaluates each
 * currently-paired device's [DeviceAlarmState] via [SeparationMonitor] every second.
 *
 * No network involved anywhere in this class (non-negotiable #1) -- detection is pure local BLE
 * scanning, no internet needed or used.
 */
class MobAlertWatchService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var evaluationJob: Job? = null
    private lateinit var wakeLock: WakeLockController
    private lateinit var pairedCrewStore: PairedCrewStore
    private var scanner: BluetoothLeScanner? = null

    private val separationMonitor = SeparationMonitor()
    private val lastSeenMillis = mutableMapOf<String, Long>()

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val deviceId = extractDeviceIdHex(result) ?: return
            val now = System.currentTimeMillis()
            lastSeenMillis[deviceId] = now
            _sightings.value = _sightings.value + (deviceId to CrewSighting(deviceId, result.rssi, now))
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE scan failed to start, errorCode=$errorCode")
            _scanError.value = scanErrorCodeToMessage(errorCode)
        }
    }

    override fun onCreate() {
        super.onCreate()
        wakeLock = WakeLockController(applicationContext, "$packageName:WatchWakeLock")
        pairedCrewStore = PairedCrewStore(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_ACKNOWLEDGE) {
            intent.getStringExtra(EXTRA_DEVICE_ID)?.let { separationMonitor.acknowledge(it) }
            return START_STICKY
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
        if (scanner == null) {
            _scanError.value = "Bluetooth scanner unavailable"
            stopSelf()
            return
        }

        wakeLock.acquire()

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(BleConstants.CREW_SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner?.startScan(listOf(filter), settings, scanCallback)
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
                val pairedIds = pairedCrewStore.getAll().map { it.deviceIdHex }.toSet()
                _alarmStates.value = separationMonitor.evaluate(pairedIds, lastSeenMillis)
                delay(EVALUATION_INTERVAL_MILLIS)
            }
        }
    }

    private fun stopScanning() {
        try {
            if (BlePermissions.hasScanPermission(this)) {
                scanner?.stopScan(scanCallback)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing runtime permission for stopScan", e)
        }
        scanner = null
    }

    override fun onDestroy() {
        evaluationJob?.cancel()
        scope.cancel()
        stopScanning()
        wakeLock.release()
        _sightings.value = emptyMap()
        _alarmStates.value = emptyMap()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun extractDeviceIdHex(result: ScanResult): String? {
        val serviceData = result.scanRecord?.getServiceData(ParcelUuid(BleConstants.CREW_SERVICE_UUID))
            ?: return null
        return serviceData.joinToString("") { "%02x".format(it) }
    }

    private fun startForegroundWithNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                BleConstants.NOTIFICATION_CHANNEL_ID,
                "Watch Mode",
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, BleConstants.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("PocketMOBAlert -- Watch mode active")
            .setContentText("Monitoring paired crew devices -- no network involved")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
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
        const val EXTRA_DEVICE_ID = "extra_device_id"

        /** Every Crew-mode beacon seen this session, paired or not -- the pairing UI offers
         * unpaired sightings as "add crew" candidates. */
        private val _sightings = MutableStateFlow<Map<String, CrewSighting>>(emptyMap())
        val sightings: StateFlow<Map<String, CrewSighting>> = _sightings.asStateFlow()

        private val _alarmStates = MutableStateFlow<Map<String, DeviceAlarmState>>(emptyMap())
        val alarmStates: StateFlow<Map<String, DeviceAlarmState>> = _alarmStates.asStateFlow()

        private val _scanError = MutableStateFlow<String?>(null)
        val scanError: StateFlow<String?> = _scanError.asStateFlow()
    }
}
