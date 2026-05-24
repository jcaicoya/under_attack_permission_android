package com.cuarzopolar.permission

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.cuarzopolar.permission.capture.CameraCapture
import com.cuarzopolar.permission.capture.SpeechManager
import com.cuarzopolar.permission.capture.VideoStreamManager
import com.cuarzopolar.permission.commands.CommandHandler
import com.cuarzopolar.permission.network.ConnectionState
import com.cuarzopolar.permission.network.MessageDispatcher
import com.cuarzopolar.permission.network.UdpDiscovery
import com.cuarzopolar.permission.network.WebSocketManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private const val TAG = "PermissionService"
private const val CHANNEL_ID = "permission_channel"
private const val CHANNEL_PRIORITY_ID = "permission_priority_channel"
private const val NOTIF_ID = 1
private const val NOTIF_BRING_TO_FRONT_ID = 2
private const val REQUEST_CODE_BRING_TO_FRONT = 100

class PermissionService : LifecycleService() {

    inner class LocalBinder : Binder() {
        fun getService(): PermissionService = this@PermissionService
    }

    private val binder = LocalBinder()

    val wsManager = WebSocketManager()
    val connectionState: StateFlow<ConnectionState> get() = wsManager.connectionState

    lateinit var commandHandler: CommandHandler
    private lateinit var dispatcher: MessageDispatcher
    private lateinit var speechManager: SpeechManager
    lateinit var cameraCapture: CameraCapture
    private lateinit var videoStreamManager: VideoStreamManager

    var onCommandReceived: ((String) -> Unit)? = null
    var onShowRedScreen: (() -> Unit)? = null
    var onHideRedScreen: (() -> Unit)? = null
    var onSendToBackground: (() -> Unit)? = null
    var onStreamStarted: (() -> Unit)? = null
    var onStreamStopped: (() -> Unit)? = null
    var isRedScreenActive = false
    var isStreamActive = false
    val isMicActive: Boolean get() = microphoneActive
    private var photoCallback: ((ByteArray) -> Unit)? = null
    private var discoveryJob: Job? = null
    private var microphoneActive = false
    private var cameraActive = false
    private var shuttingDownFromTaskRemoval = false

    fun setPhotoCallback(cb: (ByteArray) -> Unit) { photoCallback = cb }
    fun clearPhotoCallback() { photoCallback = null }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        updateForegroundService("SIN ENLACE")

        speechManager = SpeechManager(this) { transcript ->
            wsManager.sendText("""{"type":"transcript","text":"${transcript.replace("\"", "\\\"")}"}""")
        }

        videoStreamManager = VideoStreamManager { jpegBytes ->
            if (wsManager.connectionState.value == ConnectionState.CONNECTED) {
                wsManager.sendBinary(jpegBytes)
            }
        }

        cameraCapture = CameraCapture(this, this)

        commandHandler = CommandHandler(applicationContext)
        commandHandler.setMicCallbacks(
            start = {
                microphoneActive = true
                lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                    updateForegroundService(connectionStatusText())
                }
                speechManager.start()
            },
            stop  = {
                speechManager.stop()
                microphoneActive = false
                lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                    updateForegroundService(connectionStatusText())
                }
            }
        )
        commandHandler.setPhotoCallback {
            cameraCapture.takePicture(
                onResult = { bytes ->
                    wsManager.sendText("""{"type":"photo_ready"}""")
                    wsManager.sendBinary(bytes)
                },
                onError = { msg -> Log.e(TAG, "takePhoto failed: $msg") }
            )
        }

        dispatcher = MessageDispatcher(commandHandler)

        wsManager.onBinaryMessage = { bytes ->
            // Incoming binaries from Qt are always transformed photos
            photoCallback?.invoke(bytes)
        }

        wsManager.onTextMessage = { msg ->
            try {
                val obj = org.json.JSONObject(msg)
                if (obj.optString("type") == "command") {
                    when (obj.optString("action")) {
                        "showRedScreen" -> {
                            isRedScreenActive = true
                            onShowRedScreen?.invoke()
                        }
                        "hideRedScreen" -> {
                            isRedScreenActive = false
                            onHideRedScreen?.invoke()
                        }
                        "bringToForeground" -> bringAppToFront()
                        "sendToBackground" -> onSendToBackground?.invoke()
                        "wakeScreen" -> wakeAndShowApp()
                        "startStream" -> {
                            isStreamActive = true
                            videoStreamManager.startStreaming()
                            wsManager.sendText("""{"type":"stream_start"}""")
                            onStreamStarted?.invoke()
                        }
                        "stopStream" -> {
                            isStreamActive = false
                            videoStreamManager.stopStreaming()
                            wsManager.sendText("""{"type":"stream_stop"}""")
                            onStreamStopped?.invoke()
                        }
                        else -> {
                            onCommandReceived?.invoke(obj.optString("action"))
                            dispatcher.dispatch(msg)
                        }
                    }
                } else {
                    dispatcher.dispatch(msg)
                }
            } catch (_: Exception) {
                dispatcher.dispatch(msg)
            }
        }

        // Bind camera if permission already granted
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            cameraActive = true
            updateForegroundService(connectionStatusText())
            cameraCapture.bindCamera(videoStreamManager.imageAnalysis) {
                Log.d(TAG, "Camera bound")
            }
        }

        // Always try localhost first — orchestrator sets up adb reverse before launching this app.
        // On failure the retries-exhausted collector below falls back to UDP discovery.
        wsManager.connect("localhost")

        lifecycleScope.launch {
            wsManager.connectionState.collect { state ->
                if (state == ConnectionState.DISCONNECTED) {
                    resetLiveEffectsOnDisconnect()
                }
                updateNotification(connectionStatusText(state))
                if (state == ConnectionState.CONNECTED) stopDiscovery()
            }
        }

        // When retries are exhausted fall back to UDP discovery so Android reconnects
        // automatically when Qt restarts — without the user having to tap "Connect".
        lifecycleScope.launch {
            wsManager.retriesExhausted.collect { exhausted ->
                if (exhausted && !shuttingDownFromTaskRemoval) {
                    Log.d(TAG, "Retries exhausted — starting UDP discovery")
                    startDiscovery()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        stopDiscovery()
        wsManager.disconnect()
        speechManager.stop()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        shutdownFromUserExit()
        super.onTaskRemoved(rootIntent)
    }

    fun shutdownFromUserExit() {
        shuttingDownFromTaskRemoval = true
        stopDiscovery()
        resetLiveEffectsOnDisconnect()
        wsManager.disconnect()
        stopSelf()
    }

    fun connect(ip: String) {
        getSharedPreferences("permission_prefs", Context.MODE_PRIVATE)
            .edit().putString("last_ip", ip).apply()
        wsManager.connect(ip)
    }

    fun disconnect() {
        getSharedPreferences("permission_prefs", Context.MODE_PRIVATE)
            .edit().remove("last_ip").apply()
        wsManager.disconnect()
    }

    fun bindCameraIfNeeded() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            cameraActive = true
            updateForegroundService(connectionStatusText())
            cameraCapture.bindCamera(videoStreamManager.imageAnalysis) {
                Log.d(TAG, "Camera bound (deferred)")
            }
        }
    }

    fun isDeviceAdmin(): Boolean {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val comp = ComponentName(this, PermissionDeviceAdminReceiver::class.java)
        return dpm.isAdminActive(comp)
    }

    private fun bringAppToFront() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        }
        if (android.provider.Settings.canDrawOverlays(this)) {
            // SYSTEM_ALERT_WINDOW is an explicit exemption from background activity launch restrictions
            startActivity(intent)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val pi = PendingIntent.getActivity(
                this, REQUEST_CODE_BRING_TO_FRONT, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notif = Notification.Builder(this, CHANNEL_PRIORITY_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("CuarzoPolar")
                .setContentText("Volviendo al primer plano…")
                .setFullScreenIntent(pi, true)
                .setAutoCancel(true)
                .build()
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_BRING_TO_FRONT_ID, notif)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                { nm.cancel(NOTIF_BRING_TO_FRONT_ID) }, 3000L
            )
        } else {
            startActivity(intent)
        }
    }

    private fun wakeAndShowApp() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isInteractive) {
            @Suppress("DEPRECATION")
            pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "CuarzoPolar::wakeScreen"
            ).acquire(3000L)
        }
        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        })
    }

    private fun startDiscovery() {
        if (discoveryJob?.isActive == true) return
        discoveryJob = lifecycleScope.launch {
            try {
                val beacon = UdpDiscovery.awaitBeacon()
                if (wsManager.connectionState.value == ConnectionState.DISCONNECTED) {
                    connect(beacon.ip)
                }
            } catch (_: Exception) { /* cancelled or socket error */ }
        }
    }

    private fun stopDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
    }

    private fun resetLiveEffectsOnDisconnect() {
        Log.d(TAG, "Connection lost; returning permission target to normal mode")
        speechManager.stop()
        microphoneActive = false
        isStreamActive = false
        videoStreamManager.stopStreaming()
        isRedScreenActive = false
        commandHandler.handle("hideRedScreen", "")
        updateForegroundService(connectionStatusText(ConnectionState.DISCONNECTED))
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "CuarzoPolar Permission", NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                enableVibration(false)
                description = "Mantiene la conexión con la consola del operador"
            }
        )
        // HIGH importance channel required for full-screen intent on Android 10+
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PRIORITY_ID, "CuarzoPolar Alerta", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(false)
                description = "Trae la app al primer plano"
            }
        )
    }

    private fun buildNotification(statusText: String): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("CuarzoPolar")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .build()
    }

    private fun connectionStatusText(state: ConnectionState = wsManager.connectionState.value): String =
        when (state) {
            ConnectionState.CONNECTED    -> "ENLACE ACTIVO"
            ConnectionState.CONNECTING   -> "CONECTANDO\u2026"
            ConnectionState.DISCONNECTED -> "SIN ENLACE"
        }

    private fun updateForegroundService(statusText: String) {
        val types = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var t = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            if (microphoneActive) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (cameraActive)     t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            t
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(statusText), types)
    }

    private fun updateNotification(statusText: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(statusText))
    }
}
