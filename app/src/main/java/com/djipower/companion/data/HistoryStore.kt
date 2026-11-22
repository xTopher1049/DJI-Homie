package com.djipower.companion.data

import android.content.Context
import java.io.File

/**
 * Lightweight persisted telemetry history (battery %, output/input W over time).
 * Appends to a CSV in app storage, keeps a capped in-memory window, and trims
 * the file on load. No database dependency.
 */
class HistoryStore(context: Context) {

    data class Sample(val t: Long, val battery: Float, val out: Int, val inp: Int)

    private val file = File(context.filesDir, "history.csv")
    private val samples = ArrayDeque<Sample>()
    private val maxSamples = 2880            // ~24 h at a 30 s cadence
    private val minIntervalMs = 20_000L
    private var lastRecord = 0L

    init { load() }

    /** Record a sample if enough time has passed; returns true if one was added. */
    fun record(battery: Float?, out: Int?, inp: Int?): Boolean {
        if (battery == null) return false
        val now = System.currentTimeMillis()
        if (now - lastRecord < minIntervalMs) return false
        lastRecord = now
        val s = Sample(now, battery, out ?: 0, inp ?: 0)
        samples.addLast(s)
        while (samples.size > maxSamples) samples.removeFirst()
        runCatching { file.appendText("${s.t},${s.battery},${s.out},${s.inp}\n") }
        return true
    }

    fun recent(): List<Sample> = samples.toList()

    fun clear() {
        samples.clear()
        runCatching { file.writeText("") }
    }

    private fun load() {
        runCatching {
            if (!file.exists()) return
            file.readLines().takeLast(maxSamples).forEach { line ->
                val p = line.split(",")
                if (p.size == 4) {
                    val t = p[0].toLongOrNull(); val b = p[1].toFloatOrNull()
                    val o = p[2].toIntOrNull(); val i = p[3].toIntOrNull()
                    if (t != null && b != null && o != null && i != null) {
                        samples.addLast(Sample(t, b, o, i))
                    }
                }
            }
            // Trim the file back down to what we kept.
            runCatching {
                file.writeText(samples.joinToString("\n") { "${it.t},${it.battery},${it.out},${it.inp}" }
                    .let { if (it.isEmpty()) it else it + "\n" })
            }
        }
    }
}
