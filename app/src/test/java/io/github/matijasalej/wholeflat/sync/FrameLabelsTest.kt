package io.github.matijasalej.wholeflat.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameLabelsTest {
    private val ms = 1_000_000L

    /** Labels at 1000, 1033, 1067... ms (30 a second), the capture times 100 ms before and PTS 90 times the capture time. */
    private fun labels(count: Int = 10): FrameLabels {
        val l = FrameLabels()
        for (k in 0 until count) {
            val t = 1_000 * ms + k * 33_333_333L
            l.add(t, t - 100 * ms, (t - 100 * ms) / ms * 90)
        }
        return l
    }

    @Test
    fun aPictureThatComesBackLaterThanItsLabelByTheGpuIsFoundByThatLabel() {
        val l = labels()
        // the third label is at 1066.67 ms; the encoder timestamp is 20 ms after it (the drawing finished later)
        val found = l.find(1_000 * ms + 2 * 33_333_333L + 20 * ms)!!
        assertEquals(1_000 * ms + 2 * 33_333_333L - 100 * ms, found.first)
    }

    @Test
    fun aPictureThatIsAHairEarlierThanItsLabelIsStillFoundByIt() {
        val l = labels()
        // the software canvas queues inside the call: the timestamp is a little before the moment the call returned
        val found = l.find(1_000 * ms + 33_333_333L - 500_000L)!!
        assertEquals(1_000 * ms + 33_333_333L - 100 * ms, found.first)
    }

    @Test
    fun theNextLabelIsNotTakenForAPictureThatIsLateByAlmostAFrame() {
        val l = labels()
        // 30 ms after the label of the second picture, which is 3.3 ms before the label of the third
        val found = l.find(1_000 * ms + 33_333_333L + 30 * ms)!!
        assertEquals(1_000 * ms + 33_333_333L - 100 * ms, found.first)
    }

    @Test
    fun aPictureThatNoLabelFitsHasNone() {
        val l = labels()
        // 500 ms after the newest label: it is not one of ours (the encoder repeated one)
        l.find(1_000 * ms) // the first one, which takes the "first picture" rule
        assertNull(l.find(1_000 * ms + 9 * 33_333_333L + 500 * ms))
    }

    @Test
    fun theTimestampsOfAnotherClockAreLearntFromTheFirstPicture() {
        val l = labels()
        // the encoder counts from 0: the first picture is timestamped 0, the next ones 33.3 ms and so on
        val first = l.find(0)!!
        assertTrue(l.learnedOffset)
        assertEquals(1_000 * ms - 100 * ms, first.first)
        val third = l.find(2 * 33_333_333L + 3 * ms)!!
        assertEquals(1_000 * ms + 2 * 33_333_333L - 100 * ms, third.first)
        assertEquals(-1_000 * ms, -l.epochOffsetNanos)
    }

    @Test
    fun aMatchingFirstPictureLearnsNothing() {
        val l = labels()
        l.find(1_000 * ms + 5 * ms)
        assertFalse(l.learnedOffset)
        assertEquals(0L, l.epochOffsetNanos)
    }

    @Test
    fun theRingKeepsTheLastSixtyFourLabels() {
        val l = FrameLabels()
        for (k in 0 until 200) l.add(k * 33 * ms, k * 33 * ms - 100 * ms, k.toLong())
        // label 199 is there, label 100 is not
        assertEquals(199L, l.find(199 * 33 * ms + 3 * ms)!!.second)
        assertNull(l.find(100 * 33 * ms + 3 * ms))
    }
}
