package io.github.matijasalej.wholeflat.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdleFeederTest {
    private val capacity = 8_820
    private val lead = 4_410
    private val ms = 1_000_000L

    /**
     * A streaming track as it behaves on the Chromecast: nothing plays until `threshold` frames are queued,
     * then it plays 44.1 frames per millisecond, and when the queue runs dry it waits for the threshold again.
     */
    private class FakeTrack(val capacity: Int, val threshold: Int) {
        var written = 0L
        var head = 0L
        var running = false
        var startedAtMs = -1

        val queued: Long get() = written - head

        fun write(frames: Int): Int {
            val n = minOf(frames.toLong(), capacity - queued).toInt().coerceAtLeast(0)
            written += n
            return n
        }

        fun advance(atMs: Int) {
            if (!running && queued >= threshold) {
                running = true
                if (startedAtMs < 0) startedAtMs = atMs
            }
            if (running) {
                val n = minOf(queued, 44L)
                head += n
                if (queued == 0L) running = false
            }
        }
    }

    @Test
    fun aFreshTrackIsFilledUpAtOnce() {
        val feeder = IdleFeeder(lead, capacity)
        feeder.reset(0)
        assertEquals(capacity, feeder.framesToWrite(0, 0, 0))
        assertEquals(capacity - 1_024, feeder.framesToWrite(1_024, 0, 1 * ms))
        assertEquals("full", 0, feeder.framesToWrite(capacity.toLong(), 0, 2 * ms))
        assertFalse(feeder.started)
    }

    @Test
    fun theOldRuleLeavesAThresholdTrackStuckAndTheFeederStartsIt() {
        // the old idle feeding: top up to a lead of 100 ms in chunks of 1024 frames
        val old = FakeTrack(capacity, threshold = capacity)
        for (t in 0 until 1_000) {
            if (old.queued <= lead) old.write(1_024)
            old.advance(t)
        }
        assertFalse("with the lead rule the track never starts (5120 frames written)", old.running)
        assertEquals(5_120L, old.written)

        val feeder = IdleFeeder(lead, capacity)
        val track = FakeTrack(capacity, threshold = capacity)
        feeder.reset(0)
        for (t in 0 until 1_000) {
            val n = feeder.framesToWrite(track.written, track.head, t * ms)
            if (n > 0) track.write(minOf(n, 1_024))
            track.advance(t)
        }
        assertTrue("the feeder fills the buffer, so the track starts", track.startedAtMs in 0..20)
        assertTrue(feeder.started)
    }

    @Test
    fun aRunningTrackGetsOnlyTheSteadyLead() {
        val feeder = IdleFeeder(lead, capacity)
        val track = FakeTrack(capacity, threshold = 1_024)
        feeder.reset(0)
        var maxQueued = 0L
        for (t in 0 until 2_000) {
            val n = feeder.framesToWrite(track.written, track.head, t * ms)
            if (n > 0) track.write(minOf(n, 1_024))
            track.advance(t)
            if (t > 500) maxQueued = maxOf(maxQueued, track.queued)
        }
        assertTrue(track.running)
        assertTrue("queue stays near the lead, not the buffer: $maxQueued", maxQueued <= lead + 1_024)
    }

    @Test
    fun aTrackThatUnderrunsAndWaitsForTheThresholdIsFilledUpAgain() {
        val feeder = IdleFeeder(lead, capacity)
        val track = FakeTrack(capacity, threshold = capacity)
        feeder.reset(0)
        var t = 0
        fun run(ms: Int, write: Boolean) {
            repeat(ms) {
                if (write) {
                    val n = feeder.framesToWrite(track.written, track.head, t * this.ms)
                    if (n > 0) track.write(minOf(n, 1_024))
                }
                track.advance(t)
                t++
            }
        }
        run(500, write = true)
        assertTrue(track.running)
        run(400, write = false) // the writer was away: the queue runs dry, the track stops
        assertFalse(track.running)
        run(400, write = true)
        assertTrue("it is filled up again and runs", track.running)
    }

    @Test
    fun resetStartsOver() {
        val feeder = IdleFeeder(lead, capacity)
        feeder.reset(0)
        feeder.framesToWrite(1_000, 10, 1 * ms)
        assertTrue(feeder.started)
        feeder.reset(5 * ms)
        assertFalse(feeder.started)
        assertEquals(capacity, feeder.framesToWrite(0, 0, 5 * ms))
    }

    @Test
    fun neverMoreThanFitsIntoTheBuffer() {
        val feeder = IdleFeeder(lead, capacity)
        feeder.reset(0)
        for (queued in intArrayOf(0, 1_000, 8_000, 8_820)) {
            val n = feeder.framesToWrite(queued.toLong(), 0, 0)
            assertTrue(n >= 0 && n + queued <= capacity)
        }
    }

    @Test
    fun theWatchdogCallsAnUnfedTrackStarvedAndAFullOneStalled() {
        val stall = 1_200 * ms
        val startup = 8_000 * ms
        // never started, 5120 of 8820 frames written, 8 s: the writer is at fault
        assertEquals(
            StallPolicy.Verdict.STARVING,
            StallPolicy.judge(false, 8_010 * ms, 5_120, capacity, stall, startup),
        )
        // never started with a full buffer: the track is broken
        assertEquals(
            StallPolicy.Verdict.STALLED,
            StallPolicy.judge(false, 8_010 * ms, capacity.toLong(), capacity, stall, startup),
        )
        // within the grace period nothing happens
        assertEquals(StallPolicy.Verdict.OK, StallPolicy.judge(false, 7_000 * ms, 5_120, capacity, stall, startup))
        // a track that ran and stops with a half empty queue waits for data
        assertEquals(StallPolicy.Verdict.STARVING, StallPolicy.judge(true, 1_300 * ms, 3_000, capacity, stall, startup))
        assertEquals(StallPolicy.Verdict.STALLED, StallPolicy.judge(true, 1_300 * ms, capacity.toLong(), capacity, stall, startup))
        assertEquals(StallPolicy.Verdict.OK, StallPolicy.judge(true, 1_000 * ms, capacity.toLong(), capacity, stall, startup))
    }
}
