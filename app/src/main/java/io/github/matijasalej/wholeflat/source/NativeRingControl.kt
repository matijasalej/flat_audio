package io.github.matijasalej.wholeflat.source

import io.github.matijasalej.wholeflat.NativeBridge

/** The ring through JNI. */
object NativeRingControl : RingControl {
    override fun start() = NativeBridge.sourceStart()

    override fun pause() = NativeBridge.sourcePause()

    override fun flush() = NativeBridge.sourceFlush()

    override fun write(pcm: ShortArray, frames: Int, sampleRate: Int): Int =
        NativeBridge.sourceWrite(pcm, frames, sampleRate)
}
