// In: app/src/main/java/com/example/advancedvoice/Event.kt
package com.example.advancedvoice

/**
 * Wrapper for LiveData one-shot events to avoid re-handling on configuration changes.
 */
open class Event<out T>(private val content: T) {
    private var hasBeenHandled = false
    fun getContentIfNotHandled(): T? {
        return if (hasBeenHandled) null
        else {
            hasBeenHandled = true
            content
        }
    }
    fun peekContent(): T = content
}