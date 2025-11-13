package com.example.advancedvoice.domain.util

import android.util.Log

object GroundingUtils {
    private const val TAG = "GroundingUtils"

    fun processAndDisplaySources(sources: List<Pair<String, String?>>, onSystem: (String) -> Unit) {
        Log.d(TAG, "[Sources] Processing ${sources.size} source pairs")

        if (sources.isEmpty()) {
            Log.d(TAG, "[Sources] Empty list, skipping")
            return
        }

        val combined = CombinedSources()
        combined.addAll(sources)

        val html = combined.toHtmlCompact(maxItems = 5)

        if (html != null) {
            Log.i(TAG, "[Sources] ✅ Generated HTML (len=${html.length}): ${html.take(100)}...")
            onSystem(html)
            Log.d(TAG, "[Sources] onSystem callback invoked")
        } else {
            Log.w(TAG, "[Sources] ⚠️ HTML generation returned null")
        }
    }
}