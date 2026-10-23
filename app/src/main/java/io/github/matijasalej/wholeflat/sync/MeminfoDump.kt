package io.github.matijasalej.wholeflat.sync

/**
 * The processes that `dumpsys meminfo` lists, with what they hold: its section "Total RSS by process:" (else "Total PSS by process:"), whose
 * lines read `    131,060K: com.google.android.apps.tv.launcherx (pid 1570 / activities)`. An app cannot see the processes of other apps any
 * other way; with the permission `android.permission.DUMP` (granted by adb) it can run `dumpsys`. Plain Kotlin.
 */
object MeminfoDump {
    class Proc(val name: String, val pid: Int, val kb: Long) {
        /** The package: the name of the process without a `:service` part. */
        val packageName: String get() = name.substringBefore(':')
    }

    private val LINE = Regex("^\\s*([\\d,.]+)K:\\s+(\\S+)\\s+\\(pid\\s+(\\d+)")

    fun parse(text: String): List<Proc> {
        for (header in listOf("Total RSS by process:", "Total PSS by process:")) {
            val out = ArrayList<Proc>()
            var inSection = false
            for (line in text.lineSequence()) {
                if (!inSection) {
                    if (line.trim() == header) inSection = true
                    continue
                }
                if (line.isBlank()) break
                val m = LINE.find(line) ?: continue
                val kb = m.groupValues[1].replace(",", "").replace(".", "").toLongOrNull() ?: continue
                out.add(Proc(m.groupValues[2], m.groupValues[3].toInt(), kb))
            }
            if (out.isNotEmpty()) return out
        }
        return emptyList()
    }
}
