package io.github.matijasalej.wholeflat.sync

/**
 * What the lyrics video is made as (the setting `tv_video`), so that the encoder of the box can be tried at other settings without a new
 * build: the height of the picture (1080 or 720, always 16:9), the frames a second (24, 25 or 30), how the encoder spends its bits
 * ([Mode]), the bitrate it is asked for in kbit/s, and the time between key pictures in seconds. Written as text:
 * `"1080p30 cbr 6000 gop1"` (the default), `"720p30 vbr 8000"`, `"1080p25 vbr 10000 gop2"`; the order of the words does not matter and a
 * word that is left out keeps its default. Plain Kotlin.
 */
class TvVideoSpec(
    val height: Int = 1080,
    val fps: Int = 30,
    val mode: Mode = Mode.CBR,
    val kbps: Int = 6_000,
    val gopS: Int = 1,
) {
    /** How the encoder is asked to spend its bits. */
    enum class Mode(val key: String) {
        /** Constant bitrate: the same bits for every picture; a scroll that moves every pixel gets no more than a still. */
        CBR("cbr"),

        /** Variable bitrate: more bits for a picture that changes much, fewer for a still. */
        VBR("vbr"),

        /** Constant quality: the bitrate follows what the pictures need (the encoder may not offer it). */
        CQ("cq"),
    }

    val width: Int get() = height * 16 / 9

    val bitrate: Int get() = kbps * 1000

    /** The duration of one picture in 90 kHz ticks (3000 at 30 a second; 3600 at 25). */
    val frameTicks: Long get() = 90_000L / fps

    /** The rate of the transport stream (bits a second) that carries it: at least the 9 Mbit/s the TVs were tested with, and room for the peaks of the encoder. */
    val muxRate: Int get() = maxOf(9_000_000, Math.round(bitrate * 1.5).toInt())

    /** The setting's text. */
    fun text(): String = "${height}p$fps ${mode.key} $kbps gop$gopS"

    override fun toString(): String = text()

    override fun equals(other: Any?): Boolean =
        other is TvVideoSpec && other.height == height && other.fps == fps && other.mode == mode && other.kbps == kbps && other.gopS == gopS

    override fun hashCode(): Int = text().hashCode()

    companion object {
        val DEFAULT = TvVideoSpec()
        const val MIN_KBPS = 500
        const val MAX_KBPS = 20_000
        val HEIGHTS = setOf(720, 1080)
        val RATES = setOf(24, 25, 30)
        const val MAX_GOP_S = 4

        /** The spec written in [text]; null if a word of it is not understood or a number is out of range. */
        fun parse(text: String): TvVideoSpec? {
            var height = DEFAULT.height
            var fps = DEFAULT.fps
            var mode = DEFAULT.mode
            var kbps = DEFAULT.kbps
            var gop = DEFAULT.gopS
            val words = text.trim().lowercase().split(Regex("[\\s,;]+")).filter { it.isNotEmpty() }
            if (words.isEmpty()) return null
            for (w in words) {
                val size = Regex("^(\\d{3,4})p(\\d{2})?$").find(w)
                val gopWord = Regex("^gop(\\d)$").find(w)
                when {
                    size != null -> {
                        height = size.groupValues[1].toInt()
                        if (size.groupValues[2].isNotEmpty()) fps = size.groupValues[2].toInt()
                    }
                    gopWord != null -> gop = gopWord.groupValues[1].toInt()
                    w == "cbr" -> mode = Mode.CBR
                    w == "vbr" -> mode = Mode.VBR
                    w == "cq" -> mode = Mode.CQ
                    w.all { it.isDigit() } -> kbps = w.toInt()
                    else -> return null
                }
            }
            if (height !in HEIGHTS || fps !in RATES || kbps !in MIN_KBPS..MAX_KBPS || gop !in 1..MAX_GOP_S) return null
            return TvVideoSpec(height, fps, mode, kbps, gop)
        }
    }
}
