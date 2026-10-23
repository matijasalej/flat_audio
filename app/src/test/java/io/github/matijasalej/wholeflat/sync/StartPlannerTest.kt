package io.github.matijasalej.wholeflat.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StartPlannerTest {
    private val ms = 1_000_000L
    private val frameNanos = 22_676L

    /** Track frame n is presented at n / 44100 s. */
    private fun timeline() = TrackTimeline().apply { setAnchor(0, 0) }

    private fun earliestAfter(tl: TrackTimeline, nowNanos: Long, aheadMs: Long): Long =
        tl.trackOfHalNanos(nowNanos + aheadMs * ms)

    @Test
    fun withoutAKitchenTheLivingRoomStartsAtTheEarliestFrame() {
        val tl = timeline()
        val plan = StartPlanner.planImmediate(5_000, tl)
        assertEquals(5_000L, plan.firstTrackFrame)
        assertFalse(plan.delayed)
    }

    @Test
    fun bothRoomsStartTogetherWhenThereIsTime() {
        val tl = timeline()
        val now = 1_000_000_000L
        val earliest = earliestAfter(tl, now, 100) // the buffer already holds 100 ms
        val plan = StartPlanner.planTogether(now, earliest, tl, lkNanos = 290 * ms, offsetNanos = 0)
        assertEquals("Play can go out at once", now, plan.tCmdNanos)
        assertFalse(plan.delayed)
        assertEquals(now + 290 * ms, plan.kitchenSoundNanos)
        assertTrue(Math.abs(plan.livingHalNanos - (now + 290 * ms)) <= frameNanos)
        assertEquals(tl.trackNearestHalNanos(now + 290 * ms), plan.firstTrackFrame)
    }

    @Test
    fun aPositiveOffsetLetsTheLivingRoomStartEarlier() {
        val tl = timeline()
        val now = 1_000_000_000L
        val earliest = earliestAfter(tl, now, 100)
        val plan = StartPlanner.planTogether(now, earliest, tl, 290 * ms, offsetNanos = 50 * ms)
        assertEquals(now, plan.tCmdNanos)
        // the kitchen is 50 ms later than the living room
        assertTrue(Math.abs((plan.kitchenSoundNanos - plan.livingHalNanos) - 50 * ms) <= frameNanos)
        assertTrue(plan.livingHalNanos < plan.kitchenSoundNanos)
    }

    @Test
    fun playWaitsWhenTheLivingRoomCannotStartThatEarly() {
        val tl = timeline()
        val now = 1_000_000_000L
        val earliest = earliestAfter(tl, now, 100)
        // the kitchen should sound 300 ms after the living room, but Play takes 290 ms to sound: the living room
        // would have to start 10 ms before Play is even sent, and it cannot start before now + 100 ms
        val plan = StartPlanner.planTogether(now, earliest, tl, 290 * ms, offsetNanos = 300 * ms)
        assertTrue(plan.delayed)
        assertTrue("Play is held back", plan.tCmdNanos > now)
        assertTrue(plan.firstTrackFrame >= earliest)
        assertTrue(Math.abs((plan.kitchenSoundNanos - plan.livingHalNanos) - 300 * ms) <= 12 * ms)
        assertEquals("the living room starts at its earliest frame (plus the margin)", plan.firstTrackFrame,
            tl.trackNearestHalNanos(tl.halNanosOfTrack(earliest) + StartPlanner.DEFAULT_MARGIN_NANOS))
    }

    @Test
    fun aNegativeOffsetLetsTheKitchenSoundFirst() {
        val tl = timeline()
        val now = 1_000_000_000L
        val earliest = earliestAfter(tl, now, 100)
        val plan = StartPlanner.planTogether(now, earliest, tl, 290 * ms, offsetNanos = -150 * ms)
        assertEquals(now, plan.tCmdNanos)
        assertTrue(plan.livingHalNanos > plan.kitchenSoundNanos)
        assertTrue(Math.abs((plan.kitchenSoundNanos - plan.livingHalNanos) - (-150 * ms)) <= frameNanos)
    }

    @Test
    fun theLivingRoomNeverStartsBeforeItsEarliestFrame() {
        val tl = timeline()
        val now = 3_000_000_000L
        for (ahead in longArrayOf(0, 20, 100, 400)) {
            val earliest = earliestAfter(tl, now, ahead)
            for (lk in longArrayOf(150, 290, 600)) {
                var offset = -300L
                while (offset <= 300) {
                    val plan = StartPlanner.planTogether(now, earliest, tl, lk * ms, offset * ms)
                    assertTrue("ahead $ahead lk $lk offset $offset", plan.firstTrackFrame >= earliest)
                    assertTrue(plan.tCmdNanos >= now)
                    // and the relation to the kitchen holds, to a frame, unless the living room is already as early as it gets
                    val wantedLiving = plan.kitchenSoundNanos - offset * ms
                    if (plan.livingHalNanos > tl.halNanosOfTrack(earliest) + StartPlanner.DEFAULT_MARGIN_NANOS) {
                        assertTrue(Math.abs(plan.livingHalNanos - wantedLiving) <= frameNanos)
                    }
                    offset += 25
                }
            }
        }
    }

    @Test
    fun hotJoinStartsTheStreamAtTheFrameTheLivingRoomIsAboutToPlay() {
        val tl = timeline()
        tl.startRun(1_000, 0)
        tl.extend(441_000)
        // Play goes out at 2.000 s, the kitchen sounds 0.29 s later, 20 ms behind the living room:
        // the living room plays the stream's first frame at 2.270 s = track frame 100107
        assertEquals(1_000L + 100_107, StartPlanner.hotJoinFrame(tl, 2_000_000_000L, 290 * ms, 20 * ms))
    }

    @Test
    fun hotJoinNeedsTheLivingRoomToBePlaying() {
        val tl = timeline()
        assertNull(StartPlanner.hotJoinFrame(tl, 2_000_000_000L, 290 * ms, 0))
    }
}
