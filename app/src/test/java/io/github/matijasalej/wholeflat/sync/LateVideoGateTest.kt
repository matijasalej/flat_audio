package io.github.matijasalej.wholeflat.sync

import io.github.matijasalej.wholeflat.sync.LateVideoGate.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LateVideoGateTest {
    private val ms = 1_000_000L

    private fun gate(rate: Int = 9_000_000) = LateVideoGate(rate)

    @Test
    fun aPictureOnTimeIsSent() {
        val g = gate()
        assertEquals(Verdict.SEND, g.judge(false, 0, 3_000, 0))
        assertEquals(Verdict.SEND, g.judge(true, 20 * ms, 40_000, 0))
        assertFalse(g.waitingForKey)
        assertEquals(0L, g.dropped)
    }

    @Test
    fun theTimeAPictureNeedsToSendCountsAsMuchAsHowLateItIs() {
        val g = gate(3_000_000)
        // 3 Mbit/s of which video has 90 %: 60 KB take 177 ms; 400 ms late and 177 ms to send is 577 ms after the time, beyond 550 ms
        assertEquals(177L, g.sendNanos(60_000, 0) / ms)
        assertEquals(Verdict.TOO_LATE, g.judge(true, 400 * ms, 60_000, 0))
        // 300 ms late is in time
        val h = gate(3_000_000)
        assertEquals(Verdict.SEND, h.judge(true, 300 * ms, 60_000, 0))
    }

    @Test
    fun theVideoThatWaitsInTheMuxerCountsToo() {
        val g = gate()
        // 500 KB of picture are in the muxer still: 444 ms for them, so a small picture 150 ms late ends 600 ms after its time
        assertEquals(Verdict.TOO_LATE, g.judge(false, 150 * ms, 2_000, 500_000))
        assertTrue(g.waitingForKey)
    }

    @Test
    fun afterADropOnlyAKeyPictureInTimeLetsTheVideoGoOn() {
        val g = gate()
        assertEquals(Verdict.TOO_LATE, g.judge(false, 800 * ms, 3_000, 0))
        assertTrue(g.waitingForKey)
        // on time, but it refers to the one that is missing
        assertEquals(Verdict.BROKEN, g.judge(false, 0, 3_000, 0))
        assertEquals(Verdict.BROKEN, g.judge(false, 0, 3_000, 0))
        // a key picture that is late itself does not do
        assertEquals(Verdict.TOO_LATE, g.judge(true, 900 * ms, 30_000, 0))
        assertTrue(g.waitingForKey)
        // one in time does, and the pictures after it too
        assertEquals(Verdict.SEND, g.judge(true, 10 * ms, 30_000, 0))
        assertFalse(g.waitingForKey)
        assertEquals(Verdict.SEND, g.judge(false, 0, 3_000, 0))
        assertEquals(1L, g.episodes)
        assertEquals(2L, g.droppedBroken)
        assertEquals(2L, g.droppedLate)
        assertEquals(4L, g.dropped)
    }

    @Test
    fun aKeyPictureThatIsDroppedBreaksWhatFollowsToo() {
        val g = gate()
        assertEquals(Verdict.TOO_LATE, g.judge(true, 2_000 * ms, 30_000, 0))
        assertEquals(Verdict.BROKEN, g.judge(false, 0, 3_000, 0))
    }

    @Test
    fun theCountersTellWhatWasSentLateAndHowFarItWent() {
        val g = gate()
        g.judge(false, 100 * ms, 2_000, 0)
        g.judge(false, 200 * ms, 2_000, 0)
        g.judge(false, 300 * ms, 2_000, 0)
        assertEquals(2L, g.sentLate)
        assertTrue("worst ${g.worstSentEndMs}", g.worstSentEndMs in 300.0..310.0)
    }

    @Test
    fun aNegativeLatenessIsNone() {
        val g = gate()
        assertEquals(Verdict.SEND, g.judge(true, -50 * ms, 10_000, 0))
    }
}
