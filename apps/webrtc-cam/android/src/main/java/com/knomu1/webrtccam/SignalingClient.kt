package com.knomu1.webrtccam

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** PC のシグナリング用 WebSocket(/ws?role=sender)。SDP と ICE の受け渡しだけを行う。映像はここを通らない。 */
class SignalingClient(
    baseUrl: String,
    private val token: String,
    private val onMessage: (JSONObject) -> Unit,
    private val onStatus: (String) -> Unit,
) {
    private val wsUrl = baseUrl.trimEnd('/').replaceFirst("http", "ws") + "/ws?role=sender"
    private val client = OkHttpClient.Builder().pingInterval(10, TimeUnit.SECONDS).build()
    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var socket: WebSocket? = null
    @Volatile private var closed = false

    fun connect() {
        if (closed) return
        val request = Request.Builder().url(wsUrl).header("X-Api-Key", token).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "signaling open")
                onStatus("シグナリング接続済み")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    onMessage(JSONObject(text))
                } catch (e: Exception) {
                    Log.w(TAG, "bad signaling message", e)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = retry("切断($code)")

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                retry("接続失敗: ${response?.code ?: t.javaClass.simpleName}")
        })
    }

    private fun retry(reason: String) {
        Log.w(TAG, "signaling: $reason")
        onStatus("$reason。再接続中…")
        if (!closed) handler.postDelayed({ connect() }, 2000)
    }

    fun send(message: JSONObject) {
        socket?.send(message.toString())
    }

    fun close() {
        closed = true
        handler.removeCallbacksAndMessages(null)
        socket?.close(1000, "bye")
        client.dispatcher.executorService.shutdown()
    }

    private companion object {
        const val TAG = "WebRtcCam"
    }
}