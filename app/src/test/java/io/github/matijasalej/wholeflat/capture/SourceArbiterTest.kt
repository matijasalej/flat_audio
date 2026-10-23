package io.github.matijasalej.wholeflat.capture

import io.github.matijasalej.wholeflat.capture.SourceArbiter.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceArbiterTest {
    @Test
    fun nothingPlayingIsIdle() {
        val a = SourceArbiter()
        assertEquals(Source.IDLE, a.source)
        assertNull(a.librespotPlaying(false))
        assertNull(a.captureActive(false))
    }

    @Test
    fun theCastPlayerAloneIsCaptured() {
        val a = SourceArbiter()
        val change = a.captureActive(true)!!
        assertEquals(Source.IDLE, change.from)
        assertEquals(Source.CAPTURE, change.to)
        assertEquals("capture", a.source.label)
        val end = a.captureActive(false)!!
        assertEquals(Source.CAPTURE, end.from)
        assertEquals(Source.IDLE, end.to)
    }

    @Test
    fun librespotAloneIsLibrespot() {
        val a = SourceArbiter()
        assertEquals(Source.LIBRESPOT, a.librespotPlaying(true)!!.to)
        assertEquals("librespot", a.source.label)
        assertEquals(Source.IDLE, a.librespotPlaying(false)!!.to)
    }

    @Test
    fun whenBothPlayLibrespotWinsAndTheCaptureIsIgnored() {
        val a = SourceArbiter()
        a.librespotPlaying(true)
        assertNull("a capture that starts under librespot changes nothing", a.captureActive(true))
        assertEquals(Source.LIBRESPOT, a.source)
        assertTrue(a.captureWanted)
    }

    @Test
    fun librespotTakesOverFromARunningCapture() {
        val a = SourceArbiter()
        a.captureActive(true)
        val change = a.librespotPlaying(true)!!
        assertEquals(Source.CAPTURE, change.from)
        assertEquals(Source.LIBRESPOT, change.to)
    }

    @Test
    fun theCaptureTakesOverWhenLibrespotStopsAndTheCastPlayerStillPlays() {
        val a = SourceArbiter()
        a.librespotPlaying(true)
        a.captureActive(true) // ignored
        val change = a.librespotPlaying(false)!!
        assertEquals(Source.LIBRESPOT, change.from)
        assertEquals(Source.CAPTURE, change.to)
    }

    @Test
    fun whenLibrespotStopsAndTheCastPlayerHasStoppedToItIsIdle() {
        val a = SourceArbiter()
        a.librespotPlaying(true)
        a.captureActive(true)
        a.captureActive(false)
        assertEquals("librespot still plays", Source.LIBRESPOT, a.source)
        assertEquals(Source.IDLE, a.librespotPlaying(false)!!.to)
        assertFalse(a.captureWanted)
    }

    @Test
    fun aLostCaptureLeavesLibrespotAlone() {
        val a = SourceArbiter()
        a.librespotPlaying(true)
        a.captureActive(true)
        assertNull(a.captureLost())
        assertEquals(Source.LIBRESPOT, a.source)
        assertFalse(a.captureWanted)
    }

    @Test
    fun aRepeatedReportIsNotAChange() {
        val a = SourceArbiter()
        a.captureActive(true)
        assertNull(a.captureActive(true))
        a.librespotPlaying(true)
        assertNull(a.librespotPlaying(true))
    }
}
