package io.github.matijasalej.wholeflat.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaptureTargetTest {
    @Test
    fun theTargetSettingIsTheTargetAndAbsolute() {
        val t = CaptureTarget.of(kitchenTargetMs = 322, livingLatencyMs = 0)!!
        assertEquals(322.0, t.ms, 0.0)
        assertEquals("kitchen_target_ms", t.source)
    }

    @Test
    fun withoutItTheLatencyOfTheLivingRoomIsTheTarget() {
        val t = CaptureTarget.of(kitchenTargetMs = -1, livingLatencyMs = 255)!!
        assertEquals(255.0, t.ms, 0.0)
        assertEquals("capture_living_latency_ms", t.source)
    }

    @Test
    fun theTargetSettingWinsOverTheLatency() {
        val t = CaptureTarget.of(kitchenTargetMs = 240, livingLatencyMs = 255)!!
        assertEquals(240.0, t.ms, 0.0)
        assertEquals("kitchen_target_ms", t.source)
    }

    @Test
    fun aTargetOfZeroIsATargetNotOff() {
        assertEquals(0.0, CaptureTarget.of(0, 0)!!.ms, 0.0)
    }

    @Test
    fun withNeitherTheLagIsLeftWhereItLocked() {
        assertNull(CaptureTarget.of(-1, 0))
        // a latency of zero or less is no latency
        assertNull(CaptureTarget.of(-1, -10))
    }
}
