package io.github.matijasalej.wholeflat.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncedTimingTest {
    @Test
    fun withTheDefaultsTheCommonDelayIs410() {
        // X 300 ms, pipeline 80 ms, Sonos target 322 ms, margin 30 ms
        val d = SyncedTiming.delayMs(tvLatencyMs = 300.0, pipelineMs = 80.0, sonosTargetMs = 322.0)
        assertEquals(410.0, d, 0.0)
        // the TV frames are written 110 ms after their capture: 30 ms after they arrive
        assertEquals(110.0, SyncedTiming.tvHoldMs(d, 300.0), 0.0)
    }

    @Test
    fun theUsualLatencyDependsOnTheStreamFormatAndTheSettingWinsWhenGiven() {
        assertEquals(300, SyncedTiming.tvLatencyMs(-1, "l16"))
        assertEquals(1_200, SyncedTiming.tvLatencyMs(-1, "ts"))
        assertEquals(1_450, SyncedTiming.tvLatencyMs(1_450, "ts"))
        assertEquals(0, SyncedTiming.tvLatencyMs(0, "l16"))
    }

    @Test
    fun theSlowerRoomDecides() {
        // a TV that needs less than the Sonos: the Sonos target counts
        assertEquals(352.0, SyncedTiming.delayMs(150.0, 80.0, 322.0), 0.0)
        // a TV that needs more
        assertEquals(1_630.0, SyncedTiming.delayMs(1_500.0, 100.0, 322.0), 0.0)
    }

    @Test
    fun anUnmeasuredPipelineIsCountedAsEighty() {
        assertEquals(410.0, SyncedTiming.delayMs(300.0, Double.NaN, 322.0), 0.0)
        assertEquals(410.0, SyncedTiming.delayMs(300.0, -5.0, 322.0), 0.0)
    }

    @Test
    fun theHoldIsNeverShorterThanThePipelinePlusTheMargin() {
        for (x in listOf(0.0, 100.0, 300.0, 900.0)) {
            for (p in listOf(50.0, 80.0, 120.0)) {
                for (t in listOf(100.0, 322.0, 500.0)) {
                    val d = SyncedTiming.delayMs(x, p, t)
                    assertTrue("x $x p $p t $t", SyncedTiming.tvHoldMs(d, x) >= p + SyncedTiming.MARGIN_MS - 1e-9)
                    assertTrue(d >= t + SyncedTiming.MARGIN_MS - 1e-9)
                }
            }
        }
    }

    @Test
    fun theStartFrameIsTheFrameCapturedOneHoldAgo() {
        val rate = 44_100
        // a capture clock: frame f was captured at 5 s + f / rate
        val t0 = 5_000_000_000L
        val at: (Long) -> Long? = { nanos -> Math.floorDiv((nanos - t0) * rate, 1_000_000_000L) }
        val now = t0 + 3_000_000_000L // 3 s into the capture
        val frame = SyncedTiming.startFrame(now, 110_000_000L, at, fallbackFrame = 3 * 44_100L, pipelineMs = 80.0, rate = rate, oldestFrame = 0L)
        // 3.000 s - 0.110 s = 2.890 s
        assertEquals((2.89 * rate).toLong(), frame)
    }

    @Test
    fun withoutAClockTheNewestFrameLessTheHoldBeyondThePipelineIsUsed() {
        val rate = 44_100
        val newest = 200_000L
        val frame = SyncedTiming.startFrame(0L, 110_000_000L, { null }, fallbackFrame = newest, pipelineMs = 80.0, rate = rate, oldestFrame = 0L)
        // the newest frame is 80 ms old already, so 30 ms further back
        assertEquals(newest - 30 * rate / 1000, frame)
    }

    @Test
    fun theStartFrameStaysInsideTheBuffer() {
        val rate = 44_100
        // a clock that points before what the buffer holds
        val early = SyncedTiming.startFrame(10_000_000_000L, 110_000_000L, { 100L }, fallbackFrame = 500_000L, pipelineMs = 80.0, rate = rate, oldestFrame = 40_000L)
        assertEquals(40_000L, early)
        // and one that points past the newest frame
        val late = SyncedTiming.startFrame(10_000_000_000L, 110_000_000L, { 900_000L }, fallbackFrame = 500_000L, pipelineMs = 80.0, rate = rate, oldestFrame = 40_000L)
        assertEquals(500_000L, late)
    }
}
