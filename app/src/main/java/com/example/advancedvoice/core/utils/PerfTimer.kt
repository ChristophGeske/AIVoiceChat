package com.example.advancedvoice.core.util

import android.os.SystemClock
import android.util.Log

/**
 * Lightweight per-turn timer. Mark named points; print summary once.
 */
class PerfTimer(private val tag: String, private val id: String) {
    private val marks = LinkedHashMap<String, Long>()

    fun mark(name: String) {
        marks[name] = SystemClock.elapsedRealtime()
    }

    fun summary(vararg ordered: String): String {
        if (marks.isEmpty()) return "Perf($id): no marks"
        val sb = StringBuilder("Perf(").append(id).append("): ")
        var prev: Long? = null
        for (key in ordered) {
            val t = marks[key] ?: continue
            if (prev == null) {
                sb.append(key).append("=0ms; ")
            } else {
                sb.append(key).append('=').append(t - prev).append("ms; ")
            }
            prev = t
        }
        // total
        marks.values.minOrNull()?.let { first ->
            marks.values.maxOrNull()?.let { last ->
                sb.append("total=").append(last - first).append("ms")
            }
        }
        return sb.toString()
    }

    fun logSummary(vararg ordered: String) {
        Log.i(tag, summary(*ordered))
    }
}
