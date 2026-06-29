package com.cuarzopolar.permission

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.EditText
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.cuarzopolar.permission.databinding.ActivityMainBinding
import com.cuarzopolar.permission.network.ConnectionState
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var service: PermissionService? = null
    private var serviceBound = false
    private val alertHandler = Handler(Looper.getMainLooper())
    private var pulseAnimator: ObjectAnimator? = null
    private var baseScale = 1f
    private var longPressRunnable: Runnable? = null
    private var micActive = false
    private var streamActive = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as PermissionService.LocalBinder).getService()
            service = svc
            serviceBound = true
            observeConnectionState()
            svc.onCommandReceived  = { action -> runOnUiThread { onCommand(action) } }
            svc.onShowRedScreen    = { runOnUiThread { showLaserGrid() } }
            svc.onHideRedScreen    = { runOnUiThread { hideLaserGrid() } }
            svc.onSendToBackground = { runOnUiThread { moveTaskToBack(true) } }
            svc.onStreamStarted    = { runOnUiThread { streamActive = true;  updateCuarzitoColor() } }
            svc.onStreamStopped    = { runOnUiThread { streamActive = false; updateCuarzitoColor() } }
            svc.onClose            = { runOnUiThread { closeApp() } }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service?.onCommandReceived  = null
            service?.onShowRedScreen    = null
            service?.onHideRedScreen    = null
            service?.onSendToBackground = null
            service?.onStreamStarted    = null
            service?.onStreamStopped    = null
            service?.onClose            = null
            serviceBound = false
            service = null
        }
    }

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results[Manifest.permission.CAMERA] == true) {
            service?.bindCameraIfNeeded()
        }
    }

    private val deviceAdminLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* result handled silently — user may have declined */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { /* blocked intentionally */ }
        })

        applyLockScreenFlags()
        enterLockTaskIfDeviceOwner()

        val svcIntent = Intent(this, PermissionService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svcIntent)
        } else {
            startService(svcIntent)
        }
        bindService(svcIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        binding.ivCuarzito.setOnClickListener { onCuarzitoTapped() }
        binding.ivCuarzito.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    longPressRunnable = Runnable { closeApp() }
                    alertHandler.postDelayed(longPressRunnable!!, 7000L)
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> {
                    longPressRunnable?.let { alertHandler.removeCallbacks(it) }
                    longPressRunnable = null
                }
            }
            false
        }
        binding.laserGrid.setOnClickListener { showPermissionOverlay() }
        binding.btnPermissionAllow.setOnClickListener { onPermissionAllowed() }
        binding.btnPermissionDeny.setOnClickListener { dismissPermissionOverlay() }
        startPulseAnimation()
        binding.ivCuarzito.post { applyFillScale(binding.ivCuarzito, 0.85f) }

        requestInitialPermissions()
        promptDeviceAdminIfNeeded()
        promptDrawOverlaysIfNeeded()
        requestFullScreenIntentPermissionIfNeeded()
    }

    private fun observeConnectionState() {
        val svc = service ?: return
        lifecycleScope.launch {
            svc.connectionState.collectLatest { state ->
                alertHandler.removeCallbacksAndMessages(null)
                if (state == ConnectionState.DISCONNECTED) {
                    micActive = false
                    streamActive = false
                }
                binding.ivCuarzito.setImageResource(
                    when (state) {
                        ConnectionState.CONNECTED    -> R.drawable.cuarzito_green
                        ConnectionState.CONNECTING   -> R.drawable.cuarzito_amber
                        ConnectionState.DISCONNECTED -> R.drawable.cuarzito_blue
                    }
                )
            }
        }
    }

    private fun showLaserGrid() {
        binding.laserGrid.visibility = View.VISIBLE
        binding.laserGrid.animateIn()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        updateCuarzitoColor()
    }

    private fun hideLaserGrid() {
        binding.laserGrid.animateOut()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        updateCuarzitoColor()
    }

    private fun onCommand(action: String) {
        when (action) {
            "startMic" -> { micActive = true;  updateCuarzitoColor() }
            "stopMic"  -> { micActive = false; updateCuarzitoColor() }
            "vibrate"  -> flashRed(2000)
            "playSound", "takePhoto" -> flashRed(4000)
            else -> flashRed(2000)
        }
    }

    private fun flashRed(durationMs: Long) {
        alertHandler.removeCallbacksAndMessages(null)
        binding.ivCuarzito.setImageResource(R.drawable.cuarzito_red)
        alertHandler.postDelayed({ updateCuarzitoColor() }, durationMs)
    }

    private fun updateCuarzitoColor() {
        val svc = service ?: return
        if (svc.isRedScreenActive || micActive || streamActive) {
            binding.ivCuarzito.setImageResource(R.drawable.cuarzito_red)
        } else if (svc.connectionState.value == ConnectionState.CONNECTED) {
            binding.ivCuarzito.setImageResource(R.drawable.cuarzito_green)
        }
    }

    private fun applyFillScale(iv: android.widget.ImageView, targetHeightFraction: Float) {
        val vw = iv.width.toFloat()
        val vh = iv.height.toFloat()
        val d = iv.drawable ?: return
        val imgW = d.intrinsicWidth.toFloat()
        val imgH = d.intrinsicHeight.toFloat()
        if (imgW <= 0f || imgH <= 0f) return
        val fitScale = minOf(vw / imgW, vh / imgH)
        val renderedH = imgH * fitScale
        val scale = (vh * targetHeightFraction) / renderedH
        baseScale = scale
        iv.scaleX = scale
        iv.scaleY = scale
        iv.translationX = vw * 0.08f
        pulseAnimator?.cancel()
        startPulseAnimation()
    }

    private fun startPulseAnimation() {
        pulseAnimator = ObjectAnimator.ofPropertyValuesHolder(
            binding.ivCuarzito,
            PropertyValuesHolder.ofFloat("scaleX", baseScale, baseScale * 1.06f),
            PropertyValuesHolder.ofFloat("scaleY", baseScale, baseScale * 1.06f)
        ).apply {
            duration = 3000
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun onCuarzitoTapped() {
        val svc = service ?: return
        if (svc.connectionState.value == ConnectionState.CONNECTED) return
        showConnectBottomSheet()
    }

    private fun showConnectBottomSheet() {
        val sheet = BottomSheetDialog(this)
        val sheetView = LayoutInflater.from(this).inflate(R.layout.bottom_sheet_connect, null)
        sheet.setContentView(sheetView)

        val etIp = sheetView.findViewById<EditText>(R.id.etIp)
        val prefs = getSharedPreferences("permission_prefs", Context.MODE_PRIVATE)
        etIp.setText(prefs.getString("last_ip", ""))

        sheetView.findViewById<View>(R.id.btnConnect).setOnClickListener {
            val ip = etIp.text.toString().trim()
            if (ip.isNotEmpty()) {
                service?.connect(ip)
                sheet.dismiss()
            }
        }

        sheet.show()
    }

    private fun requestInitialPermissions() {
        val needed = mutableListOf<String>()
        fun needs(p: String) =
            ContextCompat.checkSelfPermission(this, p) != android.content.pm.PackageManager.PERMISSION_GRANTED
        if (needs(Manifest.permission.CAMERA))       needed.add(Manifest.permission.CAMERA)
        if (needs(Manifest.permission.RECORD_AUDIO)) needed.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            needs(Manifest.permission.POST_NOTIFICATIONS)) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isNotEmpty()) requestPermissions.launch(needed.toTypedArray())
    }

    private fun requestFullScreenIntentPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val nm = getSystemService(NotificationManager::class.java)
            if (!nm.canUseFullScreenIntent()) {
                startActivity(Intent("android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENTS").apply {
                    data = Uri.parse("package:$packageName")
                })
            }
        }
    }

    private fun promptDrawOverlaysIfNeeded() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
            })
        }
    }

    private fun promptDeviceAdminIfNeeded() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val comp = ComponentName(this, PermissionDeviceAdminReceiver::class.java)
        if (!dpm.isAdminActive(comp)) {
            val prefs = getSharedPreferences("permission_prefs", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("admin_prompted", false)) {
                prefs.edit().putBoolean("admin_prompted", true).apply()
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, comp)
                    putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "Permite bloquear el teléfono durante la escena de ataque.")
                }
                deviceAdminLauncher.launch(intent)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        service?.let { svc ->
            micActive    = svc.isMicActive
            streamActive = svc.isStreamActive
            if (svc.isRedScreenActive) showLaserGrid()
        }
        updateCuarzitoColor()
    }

    private fun showPermissionOverlay() {
        if (service?.isRedScreenActive != true) return
        binding.permissionOverlay.visibility = View.VISIBLE
    }

    private fun dismissPermissionOverlay() {
        binding.permissionOverlay.visibility = View.GONE
    }

    private fun onPermissionAllowed() {
        val bothGranted = binding.cbCamera.isChecked && binding.cbMic.isChecked
        binding.permissionOverlay.visibility = View.GONE
        if (bothGranted) {
            hideLaserGrid()
            service?.notifyPermissionsGranted()
        }
    }

    private fun closeApp() {
        stopLockTask()
        finishAndRemoveTask()
    }

    private fun enterLockTaskIfDeviceOwner() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (!dpm.isDeviceOwnerApp(packageName)) return
        val comp = ComponentName(this, PermissionDeviceAdminReceiver::class.java)
        dpm.setLockTaskPackages(comp, arrayOf(packageName))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            dpm.setLockTaskFeatures(
                comp,
                DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO or
                DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD or
                DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS
            )
        }
        startLockTask()
    }

    private fun applyLockScreenFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        pulseAnimator?.cancel()
        longPressRunnable?.let { alertHandler.removeCallbacks(it) }
        alertHandler.removeCallbacksAndMessages(null)
        if (isFinishing) service?.shutdownFromUserExit()
        if (serviceBound) {
            service?.onCommandReceived  = null
            service?.onSendToBackground = null
            service?.onStreamStarted    = null
            service?.onStreamStopped    = null
            service?.onClose            = null
            unbindService(serviceConnection)
            serviceBound = false
        }
    }
}
