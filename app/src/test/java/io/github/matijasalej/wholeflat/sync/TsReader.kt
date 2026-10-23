package io.github.matijasalej.wholeflat.sync

import java.io.ByteArrayOutputStream

/** Reads a transport stream the way a demuxer does, for the tests: packets, PES packets reassembled per PID, the PSI. */
class TsReader(val data: ByteArray) {
    class Packet(
        val index: Int,
        val pid: Int,
        val pusi: Boolean,
        val cc: Int,
        val afc: Int,
        val afLength: Int,
        val rai: Boolean,
        val pcr: Long?,
        val payload: ByteArray,
    )

    class Pes(val pid: Int, val firstPacket: Int, val streamId: Int, val declaredLength: Int, val pts: Long?, val payload: ByteArray)

    val packets = ArrayList<Packet>()
    val pes = HashMap<Int, ArrayList<Pes>>()
    val errors = ArrayList<String>()

    init {
        if (data.size % 188 != 0) errors.add("length ${data.size} is not a multiple of 188")
        val n = data.size / 188
        for (i in 0 until n) {
            val b = data.copyOfRange(i * 188, (i + 1) * 188)
            if (b[0] != 0x47.toByte()) errors.add("packet $i has no sync byte")
            val pid = ((b[1].toInt() and 0x1F) shl 8) or (b[2].toInt() and 0xFF)
            val pusi = (b[1].toInt() and 0x40) != 0
            val afc = (b[3].toInt() shr 4) and 3
            val cc = b[3].toInt() and 0xF
            var at = 4
            var afLength = -1
            var rai = false
            var pcr: Long? = null
            if (afc and 2 != 0) {
                afLength = b[4].toInt() and 0xFF
                if (afLength > 183) errors.add("packet $i: adaptation field of $afLength")
                at = 5 + afLength
                if (afLength >= 1) {
                    val flags = b[5].toInt() and 0xFF
                    rai = flags and 0x40 != 0
                    if (flags and 0x10 != 0) {
                        val base = ((b[6].toLong() and 0xFF) shl 25) or ((b[7].toLong() and 0xFF) shl 17) or
                            ((b[8].toLong() and 0xFF) shl 9) or ((b[9].toLong() and 0xFF) shl 1) or ((b[10].toLong() and 0xFF) shr 7)
                        val ext = ((b[10].toLong() and 1) shl 8) or (b[11].toLong() and 0xFF)
                        pcr = base * 300 + ext
                    }
                }
            }
            if (at > 188) errors.add("packet $i: payload starts at $at")
            val payload = if (afc and 1 != 0 && at <= 188) b.copyOfRange(at, 188) else ByteArray(0)
            packets.add(Packet(i, pid, pusi, cc, afc, afLength, rai, pcr, payload))
        }
        reassemble()
    }

    private fun reassemble() {
        val open = HashMap<Int, ByteArrayOutputStream>()
        val first = HashMap<Int, Int>()
        fun close(pid: Int) {
            val buf = open.remove(pid) ?: return
            val bytes = buf.toByteArray()
            if (bytes.size < 9 || bytes[0] != 0.toByte() || bytes[1] != 0.toByte() || bytes[2] != 1.toByte()) {
                errors.add("pid $pid: a PES does not start with a start code")
                return
            }
            val declared = ((bytes[4].toInt() and 0xFF) shl 8) or (bytes[5].toInt() and 0xFF)
            val flags = bytes[7].toInt() and 0xFF
            val headerLength = bytes[8].toInt() and 0xFF
            var pts: Long? = null
            if (flags and 0x80 != 0) pts = readTimestamp(bytes, 9)
            val payload = bytes.copyOfRange(9 + headerLength, bytes.size)
            if (declared != 0 && declared != bytes.size - 6) errors.add("pid $pid: PES declares $declared, has ${bytes.size - 6}")
            pes.getOrPut(pid) { ArrayList() }.add(Pes(pid, first[pid] ?: -1, bytes[3].toInt() and 0xFF, declared, pts, payload))
        }
        for (p in packets) {
            if (p.pid == 0 || p.pid == PMT_PID_FOR_TESTS || p.pid == 0x1FFF) continue
            if (p.pusi) {
                close(p.pid)
                open[p.pid] = ByteArrayOutputStream()
                first[p.pid] = p.index
            }
            open[p.pid]?.write(p.payload)
        }
        for (pid in open.keys.toList()) close(pid)
    }

    /** Sections of a PSI PID (the payloads that begin a section, behind the pointer field). */
    fun sections(pid: Int): List<ByteArray> {
        val result = ArrayList<ByteArray>()
        for (p in packets) {
            if (p.pid != pid || !p.pusi) continue
            val pointer = p.payload[0].toInt() and 0xFF
            val s = p.payload.copyOfRange(1 + pointer, p.payload.size)
            val length = (((s[1].toInt() and 0x0F) shl 8) or (s[2].toInt() and 0xFF)) + 3
            result.add(s.copyOf(length))
        }
        return result
    }

    companion object {
        const val PMT_PID_FOR_TESTS = 0x1000

        fun readTimestamp(b: ByteArray, off: Int): Long =
            (((b[off].toLong() shr 1) and 7) shl 30) or ((b[off + 1].toLong() and 0xFF) shl 22) or
                (((b[off + 2].toLong() and 0xFF) shr 1) shl 15) or ((b[off + 3].toLong() and 0xFF) shl 7) or ((b[off + 4].toLong() and 0xFF) shr 1)
    }
}
