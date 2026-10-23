package io.github.matijasalej.wholeflat.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaptureTargetTest {
    @Test
    fun theTargetSettingIsTheTarget() {
        val t = CaptureTarget.of(kitchenTargetMs = 240, livingLatencyMs = 0, offsetMs = 0)!!
        assertEquals(240.0, t.ms, 0.0)
        assertEquals("kitchen_target_ms", t.source)
    }

    @Test
    fun withoutItTheLatencyOfTheLivingRoomIsTheTargetPlusTheOffset() {
        val t = CaptureTarget.of(kitchenTargetMs = -1, livingLatencyMs = 255, offsetMs = 0)!!
        assertEquals(255.0, t.ms, 0.0)
        assertEquals("capture_living_latency_ms", t.source)
        assertEquals(235.0, CaptureTarget.of(-1, 255, -20)!!.ms, 0.0)
        assertEquals(290.0, CaptureTarget.of(-1, 255, 35)!!.ms, 0.0)
    }

    @Test
    fun theTargetSettingWinsOverTheLatencyAndTheOffsetIsAddedToBoth() {
        val t = CaptureTarget.of(kitchenTargetMs = 240, livingLatencyMs = 255, offsetMs = 10)!!
        assertEquals(250.0, t.ms, 0.0)
        assertEquals("kitchen_target_ms", t.source)
    }

    @Test
    fun aTargetOfZeroIsATargetNotOff() {
        assertEquals(0.0, CaptureTarget.of(0, 0, 0)!!.ms, 0.0)
    }

    @Test
    fun withNeitherTheLagIsLeftWhereItLocked() {
        assertNull(CaptureTarget.of(-1, 0, 0))
        assertNull(CaptureTarget.of(-1, 0, 50))
        // a latency of zero or less is no latency
        assertNull(CaptureTarget.of(-1, -10, 0))
    }
}
