package com.example.advancedvoice.core.prefs

import android.content.Context
import android.content.SharedPreferences

object Prefs {

    private fun sp(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PrefsKeys.FILE, Context.MODE_PRIVATE)

    // API KEYS
    fun getGeminiKey(context: Context): String =
        sp(context).getString(PrefsKeys.GEMINI_KEY, "") ?: ""

    fun setGeminiKey(context: Context, value: String) =
        sp(context).edit().putString(PrefsKeys.GEMINI_KEY, value).apply()

    fun getOpenAiKey(context: Context): String =
        sp(context).getString(PrefsKeys.OPENAI_KEY, "") ?: ""

    fun setOpenAiKey(context: Context, value: String) =
        sp(context).edit().putString(PrefsKeys.OPENAI_KEY, value).apply()

    // MODELS
    fun getSelectedModel(context: Context): String =
        sp(context).getString(PrefsKeys.SELECTED_MODEL, "gemini-2.5-flash") ?: "gemini-2.5-flash"

    fun setSelectedModel(context: Context, model: String) =
        sp(context).edit().putString(PrefsKeys.SELECTED_MODEL, model).apply()

    fun getGpt5Effort(context: Context): String =
        sp(context).getString(PrefsKeys.GPT5_EFFORT, "low") ?: "low"

    fun setGpt5Effort(context: Context, value: String) =
        sp(context).edit().putString(PrefsKeys.GPT5_EFFORT, value).apply()

    // ENGINE CONFIG
    fun getFasterFirst(context: Context): Boolean =
        sp(context).getBoolean(PrefsKeys.FASTER_FIRST, true)

    fun setFasterFirst(context: Context, enabled: Boolean) =
        sp(context).edit().putBoolean(PrefsKeys.FASTER_FIRST, enabled).apply()

    fun getMaxSentences(context: Context): Int =
        sp(context).getInt(PrefsKeys.MAX_SENTENCES, 4).coerceIn(1, 10)

    fun setMaxSentences(context: Context, value: Int) =
        sp(context).edit().putInt(PrefsKeys.MAX_SENTENCES, value.coerceIn(1, 10)).apply()

    fun getListenSeconds(context: Context): Int =
        sp(context).getInt(PrefsKeys.LISTEN_SECONDS, 5).coerceIn(1, 120)

    fun setListenSeconds(context: Context, value: Int) =
        sp(context).edit().putInt(PrefsKeys.LISTEN_SECONDS, value.coerceIn(1, 120)).apply()

    fun getAutoListen(context: Context): Boolean =
        sp(context).getBoolean(PrefsKeys.AUTO_LISTEN, true)

    fun setAutoListen(context: Context, value: Boolean) =
        sp(context).edit().putBoolean(PrefsKeys.AUTO_LISTEN, value).apply()

    // PROMPTS
    fun getSystemPrompt(context: Context, default: String): String =
        sp(context).getString(PrefsKeys.SYSTEM_PROMPT, default) ?: default

    fun setSystemPrompt(context: Context, value: String) =
        sp(context).edit().putString(PrefsKeys.SYSTEM_PROMPT, value).apply()

    fun getSystemPromptExtensions(context: Context): String =
        sp(context).getString(PrefsKeys.SYSTEM_PROMPT_EXT, "") ?: ""

    fun setSystemPromptExtensions(context: Context, value: String) =
        sp(context).edit().putString(PrefsKeys.SYSTEM_PROMPT_EXT, value).apply()
}