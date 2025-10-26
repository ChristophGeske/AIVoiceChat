package com.example.advancedvoice.core.network

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Centralized OkHttp client. Tune timeouts and add interceptors here.
 */
object HttpClientProvider {

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS) // websockets ignore readTimeout; set to 0
            .writeTimeout(60, TimeUnit.SECONDS)
            .pingInterval(45, TimeUnit.SECONDS) // was 20s; 45s reduces spurious timeouts
            .build()
    }
}
