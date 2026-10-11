package dev.peytonli.tapdrop.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TapDetectorTests {
    @Test
    fun firesWhenMagnetRiseOverlapsAccelerationBump() {
        val detector = armedDetector()
        detector.onAccel(ms(1400), 3f)

        val trigger = detector.onMagnetic(ms(1500), 600f)

        assertNotNull(trigger)
        assertEquals(600f, trigger!!.magnitudeUt)
        assertEquals(3f, trigger.linearMagnitudeMs2)
    }

    @Test
    fun magnetApproachWithoutBumpDoesNotFire() {
        val detector = armedDetector()

        assertNull(detector.onMagnetic(ms(1200), 600f))
        assertNull(detector.onMagnetic(ms(1500), 600f))
    }

    @Test
    fun bumpWithoutMagnetDoesNotFire() {
        val detector = armedDetector()

        assertNull(detector.onAccel(ms(1200), 3f))
        assertNull(detector.onMagnetic(ms(1300), 50f))
    }

    @Test
    fun restingOnMagnetDoesNotRefire() {
        val detector = armedDetector()
        detector.onMagnetic(ms(1200), 600f)
        assertNotNull(detector.onAccel(ms(1300), 3f))

        assertNull(detector.onAccel(ms(5000), 4f))
        assertNull(detector.onMagnetic(ms(5100), 600f))
    }

    @Test
    fun cooldownSuppressesTapUntilAnotherArmedRise() {
        val detector = armedDetector()
        detector.onAccel(ms(1400), 3f)
        assertNotNull(detector.onMagnetic(ms(1500), 600f))

        detector.onMagnetic(ms(1600), 50f)
        detector.onAccel(ms(2600), 3f)
        assertNull(detector.onMagnetic(ms(2700), 600f))

        detector.onMagnetic(ms(2800), 50f)
        detector.onAccel(ms(4600), 3f)
        assertNotNull(detector.onMagnetic(ms(4700), 600f))
    }

    @Test
    fun slowBaselineDriftDoesNotLookLikeMagnetApproach() {
        val detector = TapDetector()
        var trigger: TapTrigger? = null
        for (step in 0..100) {
            val time = ms(step * 100L)
            trigger = detector.onMagnetic(time, 40f + step * 0.4f)
        }

        assertNull(trigger)
        assertEquals(false, detector.state.near)
    }

    private fun armedDetector(): TapDetector = TapDetector().also {
        it.onMagnetic(ms(0), 50f)
        it.onMagnetic(ms(1100), 50f)
    }

    private fun ms(value: Long) = value * 1_000_000L
}
