package com.example.advancedvoice.core.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Small time helpers for logging and UI.
 */
object Time {
    private val sdf = ThreadLocal.withInitial {
        SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }

    fun nowString(): String = sdf.get().format(Date())

    fun format(tsMillis: Long): String = sdf.get().format(Date(tsMillis))
}
