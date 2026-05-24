package com.cuarzopolar.permission.network

import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.*
import org.json.JSONObject

private const val TAG = "WSManager"
private const val MAX_RETRIES = 3

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED }

class WebSocketManager {
    private val client = OkHttpClient.Builder()
        .build()
    private var webSocket: WebSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _state

    // Emits true when all retries are exhausted; resets to false on the next connect() call.
    private val _retriesExhausted = MutableStateFlow(false)
    val retriesExhausted: StateFlow<Boolean> = _retriesExhausted

    var onTextMessage: ((String) -> Unit)? = null
    var onBinaryMessage: ((ByteArray) -> Unit)? = null

    private var reconnectJob: Job? = null
    private var reconnectDelay = 1000L
    private var shouldReconnect = false
    private var retryCount = 0

    fun connect(ip: String, port: Int = 8765) {
        shouldReconnect = true
        reconnectDelay = 1000L
        retryCount = 0
        _retriesExhausted.value = false
        doConnect(ip, port)
    }

    private fun doConnect(ip: String, port: Int) {
        Log.d(TAG, "Connecting to ws://$ip:$port (attempt ${retryCount + 1})")
        _state.value = ConnectionState.CONNECTING
        val request = Request.Builder().url("ws://$ip:$port").build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d(TAG, "Connected to ws://$ip:$port")
                _state.value = ConnectionState.CONNECTED
                reconnectDelay = 1000L
                retryCount = 0
                val deviceName = Build.MODEL
                ws.send("""{"type":"status","deviceName":"$deviceName"}""")
            }
            override fun onMessage(ws: WebSocket, text: String) {
                val json = JSONObject(text)
                if (json.optString("type") == "ping") {
                    ws.send("""{"type":"pong"}""")
                } else {
                    onTextMessage?.invoke(text)
                }
            }
            override fun onMessage(ws: WebSocket, bytes: okio.ByteString) {
                Log.d(TAG, "Binary received: ${bytes.size} bytes")
                onBinaryMessage?.invoke(bytes.toByteArray())
            }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Connection failed: ${t::class.simpleName}: ${t.message}")
                _state.value = ConnectionState.DISCONNECTED
                scheduleReconnect(ip, port)
            }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Connection closed: $code $reason")
                _state.value = ConnectionState.DISCONNECTED
                scheduleReconnect(ip, port)
            }
        })
    }

    private fun scheduleReconnect(ip: String, port: Int) {
        if (!shouldReconnect) return
        if (retryCount >= MAX_RETRIES) {
            Log.d(TAG, "Retries exhausted after $MAX_RETRIES attempts — stopping")
            _retriesExhausted.value = true
            return
        }
        retryCount++
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            Log.d(TAG, "Retry $retryCount/$MAX_RETRIES in ${reconnectDelay}ms")
            delay(reconnectDelay)
            reconnectDelay = minOf(reconnectDelay * 2, 5_000L)
            doConnect(ip, port)
        }
    }

    fun disconnect() {
        shouldReconnect = false
        reconnectJob?.cancel()
        webSocket?.close(1000, "User disconnect")
        webSocket = null
        _state.value = ConnectionState.DISCONNECTED
    }

    fun sendText(json: String) { webSocket?.send(json) }
    fun sendBinary(data: ByteArray) { webSocket?.send(okio.ByteString.of(*data)) }
}
