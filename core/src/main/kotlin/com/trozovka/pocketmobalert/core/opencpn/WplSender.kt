package com.trozovka.pocketmobalert.core.opencpn

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Best-effort UDP broadcast of a $WPL sentence on the local network, for OpenCPN's own
 * NMEA-over-UDP network-connection input. This is a bonus notification layered on top of an
 * alarm that has already fired via BLE -- non-negotiable #1 (zero internet dependency for the
 * alarm) is unaffected either way, since this never blocks or gates the core alarm path, and it
 * only ever touches the local network (broadcast), never the internet. Failure here (no Wi-Fi,
 * no OpenCPN listening, whatever) is silently swallowed -- it must never crash or delay the
 * safety-critical alarm flow around it.
 */
object WplSender {
    private const val TAG = "WplSender"
    private const val BROADCAST_ADDRESS = "255.255.255.255"

    suspend fun send(latitude: Double, longitude: Double, waypointName: String, port: Int) {
        withContext(Dispatchers.IO) {
            try {
                val sentence = NmeaWpl.buildSentence(latitude, longitude, waypointName)
                val bytes = sentence.toByteArray(Charsets.US_ASCII)
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    val address = InetAddress.getByName(BROADCAST_ADDRESS)
                    socket.send(DatagramPacket(bytes, bytes.size, address, port))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to broadcast \$WPL to OpenCPN (non-fatal, bonus feature)", e)
            }
        }
    }
}
