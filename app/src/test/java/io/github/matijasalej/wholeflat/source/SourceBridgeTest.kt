package io.github.matijasalej.wholeflat.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceBridgeTest {
    private class Ring : RingControl {
        val calls = ArrayList<String>()
        var closed = false

        override fun start() {
            calls.add("start")
        }

        override fun pause() {
            calls.add("pause")
        }

        override fun flush() {
            calls.add("flush")
        }

        override fun write(pcm: ShortArray, frames: Int, sampleRate: Int): Int {
            calls.add("write $frames@$sampleRate")
            return when {
                closed -> -1
                sampleRate != 44_100 -> -2
                else -> frames
            }
        }
    }

    private class Status : SourceStatus {
        val calls = ArrayList<String>()

        override fun trackChanged(info: TrackInfo) {
            calls.add("track ${info.title} by ${info.artist}, ${info.durationMs} ms")
        }

        override fun playbackChanged(state: String, positionMs: Long) {
            calls.add("playback $state $positionMs")
        }

        override fun positionChanged(positionMs: Long) {
            calls.add("position $positionMs")
        }

        override fun volumeChanged(volume: Double) {
            calls.add("volume $volume")
        }
    }

    /** A source in the shape a capture would have: it gets the sink and the listener and uses only those. */
    private class FakeSource : AudioSource {
        override val name = "fake"
        var volumeAsked = -1.0

        override fun start(sink: PcmSink, listener: SourceListener) {
            listener.onTrack(TrackInfo(title = "Song", artist = "Band", durationMs = 180_000))
            listener.onStart()
            sink.write(ShortArray(2 * 441), 441, 44_100)
            listener.onFlush(30_000)
            sink.write(ShortArray(2 * 441), 441, 44_100)
            listener.onPause()
            listener.onResume()
            listener.onVolume(0.8)
        }

        override fun stop() {}

        override fun setVolume(volume: Double) {
            volumeAsked = volume
        }
    }

    @Test
    fun aSourceDrivesTheRingAndTheStatusThroughTheBridge() {
        val ring = Ring()
        val status = Status()
        val bridge = SourceBridge(ring, status)
        val source = FakeSource()
        source.start(bridge, bridge)
        assertEquals(
            listOf("start", "write 441@44100", "flush", "write 441@44100", "pause", "start"),
            ring.calls,
        )
        assertEquals(
            listOf(
                "track Song by Band, 180000 ms",
                "playback playing 0",
                "position 30000",
                "playback paused -1",
                "playback playing -1",
                "volume 0.8",
            ),
            status.calls,
        )
    }

    @Test
    fun theBridgeTakesTheRingsRateOnly() {
        val bridge = SourceBridge(Ring(), Status())
        assertEquals(listOf(44_100), bridge.supportedRates.toList())
        assertTrue(bridge.write(ShortArray(20), 10, 44_100))
        assertFalse(bridge.write(ShortArray(20), 10, 48_000))
    }

    @Test
    fun aClosedRingFailsTheWrite() {
        val ring = Ring()
        ring.closed = true
        assertFalse(SourceBridge(ring, Status()).write(ShortArray(20), 10, 44_100))
    }

    @Test
    fun theVolumeIsKeptInRangeAndTheSourceCanBeAskedToSetIt() {
        val status = Status()
        val bridge = SourceBridge(Ring(), status)
        bridge.onVolume(1.7)
        bridge.onVolume(-0.2)
        assertEquals(listOf("volume 1.0", "volume 0.0"), status.calls)
        val source = FakeSource()
        source.setVolume(0.25)
        assertEquals(0.25, source.volumeAsked, 0.0)
    }

    @Test
    fun aFlushWithoutAPositionLeavesThePositionAlone() {
        val ring = Ring()
        val status = Status()
        SourceBridge(ring, status).onFlush()
        assertEquals(listOf("flush"), ring.calls)
        assertTrue(status.calls.isEmpty())
    }
}
