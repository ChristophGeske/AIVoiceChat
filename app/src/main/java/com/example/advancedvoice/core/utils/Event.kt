package com.example.advancedvoice.core.util

/**
 * One-shot event wrapper for LiveData/Flow observers.
 */
open class Event<out T>(private val content: T) {
    private var handled = false

    fun getContentIfNotHandled(): T? {
        return if (handled) null else {
            handled = true
            content
        }
    }

    fun peek(): T = content
}
