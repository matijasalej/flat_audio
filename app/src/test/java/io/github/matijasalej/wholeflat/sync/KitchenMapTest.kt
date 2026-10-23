package io.github.matijasalej.wholeflat.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KitchenMapTest {
    @Test
    fun withoutTrimsTheStreamIsAPlainCopyOfTheRing() {
        val map = KitchenMap(firstRingFrame = 10_000)
        assertEquals(10_000.0, map.ringOfStream(0.0)!!, 1e-9)
        assertEquals(10_500.5, map.ringOfStream(500.5)!!, 1e-9)
        assertEquals(0.0, map.netDropsAt(12_345.0), 1e-9)
        assertNull(map.ringOfStream(-1.0))
    }

    @Test
    fun aDropMovesTheStreamAheadOfTheRing() {
        val map = KitchenMap(10_000)
        // at stream frame 441 one ring frame was skipped: from there the stream carries ring frame 10442 + ...
        map.addCheckpoint(441, 10_000 + 441 + 1)
        assertEquals(10_440.0, map.ringOfStream(440.0)!!, 1e-9)
        assertEquals(10_442.0, map.ringOfStream(441.0)!!, 1e-9)
        assertEquals(11_002.0, map.ringOfStream(1_001.0)!!, 1e-9)
        assertEquals(0.0, map.netDropsAt(440.0), 1e-9)
        assertEquals(1.0, map.netDropsAt(441.0), 1e-9)
        assertEquals(1.0, map.netDropsAt(5_000.0), 1e-9)
    }

    @Test
    fun aRepeatedFrameMakesTheStreamFallBack() {
        val map = KitchenMap(10_000)
        // stream frame 100 is a copy of the frame before it (ring frame 10099), from 101 on the ring goes on at 10100
        map.addCheckpoint(100, 10_099)
        assertEquals(10_099.0, map.ringOfStream(99.0)!!, 1e-9)
        assertEquals(10_099.0, map.ringOfStream(100.0)!!, 1e-9)
        assertEquals(10_100.0, map.ringOfStream(101.0)!!, 1e-9)
        assertEquals(-1.0, map.netDropsAt(101.0), 1e-9)
    }

    @Test
    fun severalTrimsAddUp() {
        val map = KitchenMap(0)
        map.addCheckpoint(441, 442) // drop
        map.addCheckpoint(882, 884) // drop
        map.addCheckpoint(1_323, 1_324) // repeat
        assertEquals(2.0, map.netDropsAt(1_322.0), 1e-9)
        assertEquals(1.0, map.netDropsAt(1_323.0), 1e-9)
        assertEquals(1.0, map.netDropsAt(10_000.0), 1e-9)
        assertEquals(4, map.checkpoints())
    }
}
