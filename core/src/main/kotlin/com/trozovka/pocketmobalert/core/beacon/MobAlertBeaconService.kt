package com.trozovka.pocketmobalert.core.beacon

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
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
import com.trozovka.pocketmobalert.core.ble.CrewDeviceIdentity
import com.trozovka.toolkit.reliability.WakeLockController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Crew mode: keeps a BLE peripheral advertisement alive with the screen off, so Watch mode can
 * detect this phone's presence/loss. No network involved anywhere in this class (non-negotiable
 * #1) -- advertising is pure local Bluetooth radio, no internet needed or used.
 */
class MobAlertBeaconService : Service() {

    private lateinit var wakeLock: WakeLockController
    private var advertiser: BluetoothLeAdvertiser? = null

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            _status.value = BeaconStatus.Advertising
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "BLE advertising failed to start, errorCode=$errorCode")
            _status.value = BeaconStatus.Error(errorCodeToMessage(errorCode))
            wakeLock.release()
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        wakeLock = WakeLockController(applicationContext, "$packageName:CrewBeaconWakeLock")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundWithNotification()
        startAdvertising()
        return START_STICKY
    }

    private fun startAdvertising() {
        if (!BlePermissions.hasAdvertisePermission(this) || !BlePermissions.hasConnectPermission(this)) {
            _status.value = BeaconStatus.Error("Bluetooth permission not granted")
            stopSelf()
            return
        }

        val bluetoothManager = getSystemService(BluetoothManager::class.java)
        val adapter = bluetoothManager?.adapter
        if (adapter == null) {
            _status.value = BeaconStatus.Error("This device has no Bluetooth radio")
            stopSelf()
            return
        }
        if (!adapter.isEnabled) {
            _status.value = BeaconStatus.Error("Bluetooth is turned off")
            stopSelf()
            return
        }
        if (!adapter.isMultipleAdvertisementSupported) {
            _status.value = BeaconStatus.Error(
                "This phone's Bluetooth chipset doesn't support advertising (peripheral mode) -- " +
                    "it can't run Crew mode. It can still run Watch mode.",
            )
            stopSelf()
            return
        }

        advertiser = adapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            _status.value = BeaconStatus.Error("Bluetooth advertiser unavailable")
            stopSelf()
            return
        }

        wakeLock.acquire()

        val deviceId = CrewDeviceIdentity.getOrCreate(applicationContext)
        _deviceIdHex.value = CrewDeviceIdentity.getOrCreateHex(applicationContext)

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()

        // Primary packet: just flags + service UUID, so Watch mode's scan filter matches even
        // on a passive scan. The per-install device ID goes in the scan response instead --
        // a 128-bit service UUID plus its own service-data structure won't both fit in one
        // 31-byte legacy advertising packet.
        val advertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(BleConstants.CREW_SERVICE_UUID))
            .build()

        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceData(ParcelUuid(BleConstants.CREW_SERVICE_UUID), deviceId)
            .build()

        try {
            advertiser?.startAdvertising(settings, advertiseData, scanResponse, advertiseCallback)
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing runtime permission for startAdvertising", e)
            _status.value = BeaconStatus.Error("Bluetooth permission not granted")
            wakeLock.release()
            stopSelf()
        }
    }

    private fun stopAdvertising() {
        try {
            if (BlePermissions.hasAdvertisePermission(this)) {
                advertiser?.stopAdvertising(advertiseCallback)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing runtime permission for stopAdvertising", e)
        }
        advertiser = null
    }

    override fun onDestroy() {
        stopAdvertising()
        wakeLock.release()
        _status.value = BeaconStatus.Stopped
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundWithNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                BleConstants.NOTIFICATION_CHANNEL_ID,
                "Crew Mode",
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, BleConstants.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("PocketMOBAlert -- Crew mode active")
            .setContentText("Broadcasting to nearby Watch-mode devices -- no network involved")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun errorCodeToMessage(errorCode: Int): String = when (errorCode) {
        AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "Advertising already started"
        AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "Advertising data too large"
        AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "This device doesn't support BLE advertising"
        AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "Internal Bluetooth error"
        AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "Too many apps advertising at once"
        else -> "Unknown Bluetooth advertising error ($errorCode)"
    }

    sealed class BeaconStatus {
        data object Stopped : BeaconStatus()
        data object Advertising : BeaconStatus()
        data class Error(val message: String) : BeaconStatus()
    }

    companion object {
        private const val TAG = "MobAlertBeaconService"
        private const val NOTIFICATION_ID = 2

        private val _status = MutableStateFlow<BeaconStatus>(BeaconStatus.Stopped)
        val status: StateFlow<BeaconStatus> = _status.asStateFlow()

        private val _deviceIdHex = MutableStateFlow<String?>(null)
        val deviceIdHex: StateFlow<String?> = _deviceIdHex.asStateFlow()
    }
}
