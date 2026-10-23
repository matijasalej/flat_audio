package io.github.matijasalej.wholeflat.light

/**
 * What the lights know of the music at one moment, in capture frames: the tempo and a beat to count the grid from, how clear the beat is,
 * the energy now and of the song, the mood, the level, and the section events. Made by [Analyzer], read by the painter; never changed.
 */
class Analysis(
    /** The capture frame at which this song's analysis began. */
    val firstFrame: Long,
    /** The newest capture frame analysed. */
    val frame: Long,
    /** The tempo (NaN until there is one). */
    val bpm: Double,
    /** The beat period in capture frames (NaN until there is a tempo). */
    val periodFrames: Double,
    /** A beat, in capture frames (fractional; NaN until there is a tempo); the grid is this plus whole periods. */
    val beatFrame: Double,
    /** The tempo has held for a while and the beat is clear enough to pulse on. */
    val locked: Boolean,
    /** How clear the beat is (0 to 1). */
    val clarity: Double,
    val energyNow: Double,
    val energySong: Double,
    val mood: Double,
    /** -1 minor to 1 major (0 while unsure). */
    val mode: Double,
    /** The level of the last second (dBFS). */
    val loudDb: Double,
    val silent: Boolean,
    val sections: List<Section>,
) {
    /** A section event at capture frame [frame]: the music got louder ([up]) or quieter. */
    class Section(val frame: Long, val up: Boolean)

    /** Seconds of the song analysed. */
    fun songSeconds(rate: Int): Double = (frame - firstFrame).toDouble() / rate

    companion object {
        val EMPTY = Analysis(0, 0, Double.NaN, Double.NaN, Double.NaN, false, 0.0, 0.0, 0.0, 0.5, 0.0, Onsets.FLOOR_DB, true, emptyList())
    }
}

/**
 * The analysis of the music as it is captured: the hops of [Onsets] go into the onset envelope and [SongFeatures]; every half second
 * the [Tempo] is read again, and the beat grid is **locked** once three readings in a row agree (the lights only pulse on a locked
 * grid). A locked grid follows small changes and gives way to a new tempo only when four readings in a row want it. The latest
 * state is [snapshot], published every 50 ms for the painter on another thread.
 *
 * [reset] begins a song (or a new capture): a tempo remembered for the song can be given as the centre of the prior. Plain Kotlin;
 * one thread pushes, any thread reads [snapshot].
 */
class Analyzer(val rate: Int = 44100) {
    private val onsets = Onsets(rate)
    private val features = SongFeatures(HOPS_PER_SECOND)
    private val tempo = Tempo(HOPS_PER_SECOND.toDouble())
    private val hop = onsets.hop
    private val ring = DoubleArray(ENV_HOPS)
    private val envCopy = DoubleArray(ENV_HOPS)
    private var hops = 0L
    private var firstFrame = 0L

    private var prior = Tempo.PRIOR_BPM
    private val recent = ArrayList<Tempo.Estimate>()
    private var locked = false
    private var periodHops = Double.NaN
    private var beatHop = Double.NaN
    private var clarity = 0.0
    private var disagree = 0
    private var unclear = 0

    @Volatile
    var snapshot: Analysis = Analysis.EMPTY
        private set

    /** Starts a song at capture frame [atFrame]; [priorBpm] is the tempo remembered for it, if any. */
    fun reset(atFrame: Long, priorBpm: Double? = null) {
        onsets.reset()
        features.reset()
        ring.fill(0.0)
        hops = 0
        firstFrame = atFrame
        prior = priorBpm?.takeIf { it in Tempo.MIN_BPM..Tempo.MAX_BPM } ?: Tempo.PRIOR_BPM
        recent.clear()
        locked = false
        periodHops = Double.NaN
        beatHop = Double.NaN
        clarity = 0.0
        disagree = 0
        unclear = 0
        snapshot = Analysis(atFrame, atFrame, Double.NaN, Double.NaN, Double.NaN, false, 0.0, 0.0, 0.0, 0.5, 0.0, Onsets.FLOOR_DB, true, emptyList())
    }

    /** The next [frames] frames of [pcm] (interleaved stereo), following on from the last ones. */
    fun push(pcm: ShortArray, frames: Int) {
        onsets.push(pcm, frames) { h -> onHop(h) }
    }

    private fun onHop(h: Onsets.Hop) {
        ring[(hops % ENV_HOPS).toInt()] = envelopeOf(h)
        hops++
        features.add(h)
        if (hops % (HOPS_PER_SECOND / 2) == 0L) readTempo()
        if (hops % PUBLISH_EVERY == 0L) publish()
    }

    private fun readTempo() {
        val n = minOf(hops, ENV_HOPS.toLong()).toInt()
        for (j in 0 until n) envCopy[ENV_HOPS - n + j] = ring[((hops - n + j) % ENV_HOPS).toInt()]
        val lastHop = hops - 1
        val expectedOffset = if (beatHop.isNaN()) null else Tempo.circular(lastHop - beatHop, periodHops).let { if (it < 0) it + periodHops else it }
        val e = tempo.estimate(envCopy, count = n, priorBpm = prior, expectedOffset = expectedOffset, expectedPeriod = if (periodHops.isNaN()) null else periodHops)
        if (e == null) {
            if (++unclear >= 4) unlock()
            return
        }
        clarity = if (recent.isEmpty() && !locked) e.clarity else 0.7 * clarity + 0.3 * e.clarity
        recent.add(e)
        while (recent.size > 4) recent.removeAt(0)
        if (e.clarity < UNLOCK_CLARITY) unclear++ else unclear = 0
        if (unclear >= 4) {
            unlock()
            return
        }
        val beat = lastHop - e.offsetHops
        if (!locked) {
            val last3 = recent.takeLast(3)
            if (last3.size == 3 && last3.all { same(it.periodHops, e.periodHops, LOCK_AGREE) } && clarity >= LOCK_CLARITY) {
                locked = true
                disagree = 0
            }
            periodHops = e.periodHops
            beatHop = beat
            return
        }
        if (same(e.periodHops, periodHops, FOLLOW_AGREE)) {
            disagree = 0
            periodHops = e.periodHops
            beatHop = beat
        } else if (++disagree >= 4) {
            // the song changed its tempo: take the new one if the last readings agree on it
            val last3 = recent.takeLast(3)
            if (last3.all { same(it.periodHops, e.periodHops, LOCK_AGREE) }) {
                periodHops = e.periodHops
                beatHop = beat
                disagree = 0
            } else {
                unlock()
            }
        }
    }

    private fun unlock() {
        locked = false
        disagree = 0
    }

    private fun publish() {
        val lastFrame = firstFrame + hops * hop
        val bpm = if (periodHops.isNaN()) Double.NaN else 60.0 * HOPS_PER_SECOND / periodHops
        val beatFrame = if (beatHop.isNaN()) Double.NaN else firstFrame + (beatHop + 1) * hop - Onsets.ONSET_LAG_FRAMES
        val sections = features.sections().map { Analysis.Section(firstFrame + it.hop * hop, it.up) }
        snapshot = Analysis(
            firstFrame = firstFrame,
            frame = lastFrame,
            bpm = bpm,
            periodFrames = if (periodHops.isNaN()) Double.NaN else periodHops * hop,
            beatFrame = beatFrame,
            locked = locked,
            clarity = clarity.coerceIn(0.0, 1.0),
            energyNow = features.energyNow(),
            energySong = features.energySong(),
            mood = features.mood(bpm),
            mode = features.mode().first,
            loudDb = features.loudDb(1.0),
            silent = features.silent(),
            sections = sections,
        )
    }

    companion object {
        const val HOPS_PER_SECOND = 100
        const val ENV_HOPS = 800
        const val PUBLISH_EVERY = 5L
        const val LOCK_AGREE = 0.04
        const val FOLLOW_AGREE = 0.06
        const val LOCK_CLARITY = 0.2
        const val UNLOCK_CLARITY = 0.08

        /** The onset envelope of a hop: the flux of the whole band with a little of the bass, where the kick is. */
        fun envelopeOf(h: Onsets.Hop): Double = h.flux + 0.1 * h.bassFlux

        private fun same(a: Double, b: Double, share: Double): Boolean = Math.abs(a / b - 1.0) <= share
    }
}
