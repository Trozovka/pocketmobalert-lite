package com.trozovka.pocketmobalert.core.watch

import org.junit.Assert.assertEquals
import org.junit.Test

class SeparationMonitorTest {

    private class FakeClock(var millis: Long = 0L) {
        fun advance(by: Long) { millis += by }
    }

    @Test
    fun `device seen recently is Present`() {
        val clock = FakeClock(100_000L)
        val monitor = SeparationMonitor(now = { clock.millis })
        val result = monitor.evaluate(setOf("crew1"), mapOf("crew1" to clock.millis - 1_000L))
        assertEquals(DeviceAlarmState.Present, result["crew1"])
    }

    @Test
    fun `device silent past miss threshold but under confirmation window is PossibleSeparation`() {
        val clock = FakeClock(100_000L)
        val monitor = SeparationMonitor(now = { clock.millis })
        val result = monitor.evaluate(setOf("crew1"), mapOf("crew1" to clock.millis - 5_000L))
        assertEquals(DeviceAlarmState.PossibleSeparation, result["crew1"])
    }

    @Test
    fun `device silent past both windows is Alarming`() {
        val clock = FakeClock(100_000L)
        val monitor = SeparationMonitor(now = { clock.millis })
        val result = monitor.evaluate(setOf("crew1"), mapOf("crew1" to clock.millis - 9_000L))
        assertEquals(DeviceAlarmState.Alarming, result["crew1"])
    }

    @Test
    fun `never-seen paired device is Alarming immediately`() {
        val clock = FakeClock(100_000L)
        val monitor = SeparationMonitor(now = { clock.millis })
        val result = monitor.evaluate(setOf("crew1"), emptyMap())
        assertEquals(DeviceAlarmState.Alarming, result["crew1"])
    }

    @Test
    fun `brief dropout that reappears within confirmation window returns to Present, never alarms`() {
        val clock = FakeClock(100_000L)
        val monitor = SeparationMonitor(now = { clock.millis })

        // Silence begins.
        val silenceStarted = clock.millis
        clock.advance(6_000L) // 6s silence -> PossibleSeparation, not yet Alarming
        var result = monitor.evaluate(setOf("crew1"), mapOf("crew1" to silenceStarted))
        assertEquals(DeviceAlarmState.PossibleSeparation, result["crew1"])

        // Signal reacquired.
        clock.advance(500L)
        val reacquiredAt = clock.millis
        result = monitor.evaluate(setOf("crew1"), mapOf("crew1" to reacquiredAt))
        assertEquals(DeviceAlarmState.Present, result["crew1"])
    }

    @Test
    fun `alarm latches and does not clear on signal reacquisition without acknowledge`() {
        val clock = FakeClock(100_000L)
        val monitor = SeparationMonitor(now = { clock.millis })

        val silenceStarted = clock.millis
        clock.advance(9_000L) // past both windows
        var result = monitor.evaluate(setOf("crew1"), mapOf("crew1" to silenceStarted))
        assertEquals(DeviceAlarmState.Alarming, result["crew1"])

        // Signal comes back right now -- alarm must still latch as Alarming.
        clock.advance(100L)
        val reacquiredAt = clock.millis
        result = monitor.evaluate(setOf("crew1"), mapOf("crew1" to reacquiredAt))
        assertEquals(DeviceAlarmState.Alarming, result["crew1"])
    }

    @Test
    fun `acknowledge clears the latch and lets state re-evaluate fresh`() {
        val clock = FakeClock(100_000L)
        val monitor = SeparationMonitor(now = { clock.millis })

        val silenceStarted = clock.millis
        clock.advance(9_000L)
        monitor.evaluate(setOf("crew1"), mapOf("crew1" to silenceStarted))
        assertEquals(true, monitor.isLatched("crew1"))

        monitor.acknowledge("crew1")
        assertEquals(false, monitor.isLatched("crew1"))

        // Now seen recently -- should read Present again, not stuck Alarming.
        val recentlySeen = clock.millis
        val result = monitor.evaluate(setOf("crew1"), mapOf("crew1" to recentlySeen))
        assertEquals(DeviceAlarmState.Present, result["crew1"])
    }

    @Test
    fun `independent devices tracked independently`() {
        val clock = FakeClock(100_000L)
        val monitor = SeparationMonitor(now = { clock.millis })
        val result = monitor.evaluate(
            setOf("crew1", "crew2"),
            mapOf("crew1" to clock.millis - 1_000L, "crew2" to clock.millis - 9_000L),
        )
        assertEquals(DeviceAlarmState.Present, result["crew1"])
        assertEquals(DeviceAlarmState.Alarming, result["crew2"])
    }
}
