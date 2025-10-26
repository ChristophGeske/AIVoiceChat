package com.example.advancedvoice.core.network

import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Thin factory over OkHttp's WebSocket creation. Keeps all sockets using the shared client.
 */
object WebSocketProvider {

    fun newWebSocket(
        url: String,
        headers: Map<String, String> = emptyMap(),
        listener: WebSocketListener
    ): WebSocket {
        val reqBuilder = Request.Builder().url(url)
        headers.forEach { (k, v) -> reqBuilder.addHeader(k, v) }
        val request = reqBuilder.build()
        return HttpClientProvider.client.newWebSocket(request, listener)
    }
}
