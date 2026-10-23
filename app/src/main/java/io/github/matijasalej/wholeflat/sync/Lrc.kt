package io.github.matijasalej.wholeflat.sync

/** One line of synced lyrics: the time it begins at (ms from the start of the track) and its text (empty for a gap). */
class LyricLine(val timeMs: Long, val text: String)

/**
 * Synced lyrics in the LRC format (what LRCLIB gives as `syncedLyrics`): lines like `[01:23.45] text`, several time tags in front of
 * one text (a refrain), minutes and seconds with 2 or 3 digits of fraction or none, `[offset:+500]`, other tags (`[ar:]`, `[ti:]`)
 * ignored, and the word times of the enhanced format (`<01:23.45>`) removed from the text. Plain Kotlin.
 */
object Lrc {
    private val TIME_TAG = Regex("^\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
    private val OFFSET_TAG = Regex("^\\[offset:\\s*([+-]?\\d+)\\s*]", RegexOption.IGNORE_CASE)
    private val WORD_TAG = Regex("<\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?>")

    /** The lines of [text], in time order (equal times keep the order of the text). Text that has no time tag gives none. */
    fun parse(text: String): List<LyricLine> {
        var offset = 0L
        val result = ArrayList<LyricLine>()
        for (raw in text.split('\n')) {
            var line = raw.trim().trimStart('﻿')
            if (line.isEmpty()) continue
            val offsetTag = OFFSET_TAG.find(line)
            if (offsetTag != null) {
                offset = offsetTag.groupValues[1].toLongOrNull() ?: 0L
                continue
            }
            val times = ArrayList<Long>()
            while (true) {
                val m = TIME_TAG.find(line) ?: break
                val minutes = m.groupValues[1].toLong()
                val seconds = m.groupValues[2].toLong()
                val fraction = m.groupValues[3]
                val ms = when (fraction.length) {
                    0 -> 0L
                    1 -> fraction.toLong() * 100
                    2 -> fraction.toLong() * 10
                    else -> fraction.toLong()
                }
                times.add(minutes * 60_000L + seconds * 1_000L + ms)
                line = line.substring(m.value.length).trimStart()
            }
            if (times.isEmpty()) continue
            val words = WORD_TAG.replace(line, "").replace(Regex("\\s+"), " ").trim()
            for (t in times) result.add(LyricLine(maxOf(0L, t - offset), words))
        }
        // a stable sort: lines with the same time stay in the order they came
        return result.sortedBy { it.timeMs }
    }

    /** The index of the line that is current at [positionMs] (the last one that has begun), -1 before the first. */
    fun lineAt(lines: List<LyricLine>, positionMs: Long): Int {
        var lo = 0
        var hi = lines.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].timeMs <= positionMs) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return found
    }
}
