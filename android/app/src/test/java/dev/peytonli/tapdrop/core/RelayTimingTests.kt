package dev.peytonli.tapdrop.core

import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Test

class RelayTimingTests {
    @Test
    fun pingAndPongDefaultsAreMeasuredInSeconds() {
        assertEquals(25.seconds, settingSeconds("TAPDROP_PING_INTERVAL_SECONDS", 25) { null })
        assertEquals(10.seconds, settingSeconds("TAPDROP_PONG_TIMEOUT_SECONDS", 10) { null })
    }

    @Test
    fun pingIntervalOverrideIsMeasuredInSeconds() {
        assertEquals(3.seconds, settingSeconds("TAPDROP_PING_INTERVAL_SECONDS", 25) { "3" })
    }
}
