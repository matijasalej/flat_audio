package io.github.matijasalej.wholeflat.capture

import io.github.matijasalej.wholeflat.sync.FrameSource

/**
 * The last seconds of the captured audio: interleaved 16 bit stereo frames at the capture's own sample rate
 * (44.1 kHz), numbered from the start of the capture ("generation"). One writer (the capture thread), any
 * number of readers (the stream to the Sonos), which read by absolute frame index and do not consume.
 *
 * Unlike the ring of the Spotify engine, the writer never waits: a capture cannot be slowed down, so a
 * reader that falls behind by more than the capacity is lapped. A new capture (the projection was lost and
 * acquired again) starts a new generation and numbers its frames from 0; readers of an older generation are
 * told so and give up.
 */
class CaptureBuffer(val rate: Int, private val capacityFrames: Int) {
    private val data = ShortArray(capacityFrames * 2)
    private val lock = Object()
    private var writeIdx = 0L
    private var gen = 0L
    private var closed = false

    val generation: Long get() = synchronized(lock) { gen }

    /** Number of frames written in this generation. */
    val writeIndex: Long get() = synchronized(lock) { writeIdx }

    /** The oldest frame that is still in the buffer. */
    val validStart: Long get() = synchronized(lock) { maxOf(0L, writeIdx - capacityFrames) }

    /** A new capture begins: numbering restarts at 0 and readers of the old one are told. */
    fun newGeneration(generation: Long) {
        synchronized(lock) {
            gen = generation
            writeIdx = 0L
            lock.notifyAll()
        }
    }

    fun close() {
        synchronized(lock) {
            closed = true
            lock.notifyAll()
        }
    }

    fun open() {
        synchronized(lock) { closed = false }
    }

    /** Appends [frames] frames of [pcm] (interleaved stereo). */
    fun write(pcm: ShortArray, frames: Int) {
        if (frames <= 0) return
        synchronized(lock) {
            // a chunk bigger than the whole buffer keeps only its end
            val skip = maxOf(0, frames - capacityFrames)
            val count = frames - skip
            var idx = writeIdx + skip
            var src = skip * 2
            var left = count
            while (left > 0) {
                val pos = (idx % capacityFrames).toInt()
                val n = minOf(left, capacityFrames - pos)
                System.arraycopy(pcm, src, data, pos * 2, n * 2)
                idx += n
                src += n * 2
                left -= n
            }
            writeIdx += frames
            lock.notifyAll()
        }
    }

    /**
     * Copies up to [maxFrames] frames starting at [cursor] into [out]. Waits up to [timeoutMs] for the
     * cursor to be written. Returns the number of frames, 0 on timeout, or [FrameSource.CLOSED], [FrameSource.LAPPED]
     * (the frames are gone) or [FrameSource.EPOCH] (this is not the generation the reader belongs to).
     */
    fun read(cursor: Long, generation: Long, out: ShortArray, maxFrames: Int, timeoutMs: Int): Int {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        synchronized(lock) {
            while (true) {
                if (closed) return FrameSource.CLOSED
                if (gen != generation) return FrameSource.EPOCH
                if (cursor < writeIdx - capacityFrames) return FrameSource.LAPPED
                if (cursor < writeIdx) {
                    val n = minOf((writeIdx - cursor).toInt().coerceAtLeast(1), maxFrames)
                    var idx = cursor
                    var dst = 0
                    var left = n
                    while (left > 0) {
                        val pos = (idx % capacityFrames).toInt()
                        val m = minOf(left, capacityFrames - pos)
                        System.arraycopy(data, pos * 2, out, dst, m * 2)
                        idx += m
                        dst += m * 2
                        left -= m
                    }
                    return n
                }
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return 0
                lock.wait(maxOf(1L, remaining / 1_000_000L))
            }
        }
    }
}

/** The capture buffer as the frame source of a stream (see [FrameSource]). */
class CaptureFrames(private val buffer: CaptureBuffer, private val generation: Long) : FrameSource {
    override fun read(cursor: Long, out: ShortArray, maxFrames: Int, timeoutMs: Int): Int =
        buffer.read(cursor, generation, out, maxFrames, timeoutMs)
}
