package com.trozovka.pocketmobalert.core.opencpn

import org.junit.Assert.assertEquals
import org.junit.Test

class NmeaWplTest {

    @Test
    fun `builds a correct sentence for a known position, checksum hand-verified independently`() {
        // 48 degrees 07.038 minutes N, 011 degrees 31.000 minutes E -- checksum for
        // "GPWPL,4807.038,N,01131.000,E,MOB" independently computed via a standalone Python
        // XOR script (not this implementation) as 0x09.
        val latitude = 48.0 + 7.038 / 60.0
        val longitude = 11.0 + 31.0 / 60.0

        val sentence = NmeaWpl.buildSentence(latitude, longitude, "MOB")

        assertEquals("\$GPWPL,4807.038,N,01131.000,E,MOB*09\r\n", sentence)
    }

    @Test
    fun `southern and western hemispheres use correct letters`() {
        val sentence = NmeaWpl.buildSentence(-33.8688, -151.2093, "MOB")
        assertEquals(true, sentence.contains(",S,"))
        assertEquals(true, sentence.contains(",W,"))
    }

    @Test
    fun `degree fields are zero-padded to the correct width`() {
        // Small single-digit-degree position should still produce 2-digit lat / 3-digit lon
        // degree prefixes ("05", "005"), not bare "5"/"5".
        val sentence = NmeaWpl.buildSentence(5.5, 5.5, "MOB")
        val body = sentence.substringAfter("$").substringBefore("*")
        val fields = body.split(",")
        assertEquals("0530.000", fields[1])
        assertEquals("00530.000", fields[3])
    }

    @Test
    fun `sentence ends with CRLF as required by NMEA-0183`() {
        val sentence = NmeaWpl.buildSentence(0.0, 0.0, "MOB")
        assertEquals(true, sentence.endsWith("\r\n"))
    }
}
