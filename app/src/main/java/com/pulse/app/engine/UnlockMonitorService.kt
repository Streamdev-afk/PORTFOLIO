package com.pulse.app.engine

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.pulse.app.diagnostics.EventLog
import com.pulse.app.settings.PocPrefs

/**
 * Phase 1 unlock monitor.
 *
 * WHAT IT CAN KNOW: ACTION_USER_PRESENT means "the keyguard was dismissed and the user is
 * present". It does NOT say HOW the device was unlocked (fingerprint / PIN / pattern / face
 * / swipe). So this is an "any unlock" trigger, and the UI must label it that way.
 *
 * Receivers are registered dynamically (not in the manifest) because this is the reliable
 * pattern for screen/unlock broadcasts; the foreground service keeps the process alive.
 * No timers, no polling, no wake locks: the service is idle until Android delivers an event.
 */
class UnlockMonitorService : Service() {

    private lateinit var overlay: RippleOverlay
    private var lastTriggerAt = 0L

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val now = SystemClock.elapsedRealtime()
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    EventLog.add(context, "SCREEN_OFF", snapshot())
                    overlay.dismiss("screen off") // stop any rendering when the display turns off
                }
                Intent.ACTION_SCREEN_ON -> EventLog.add(context, "SCREEN_ON", snapshot())
                Intent.ACTION_USER_PRESENT -> {
                    EventLog.add(context, "USER_PRESENT", snapshot())
                    // Debounce: ignore duplicate deliveries / rapid lock-unlock sequences.
                    if (now - lastTriggerAt < DEBOUNCE_MS) {
                        EventLog.add(context, "TRIGGER", "ignored (debounce ${now - lastTriggerAt}ms)")
                        return
                    }
                    lastTriggerAt = now
                    overlay.show(PocPrefs.originX(context), PocPrefs.originY(context), triggerElapsedMs = now)
                }
            }
        }
    }

    /** Records the system state at the moment of an event. Pure observation, nothing is changed. */
    private fun snapshot(): String {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return "interactive=${pm.isInteractive} keyguardLocked=${km.isKeyguardLocked} " +
            "deviceLocked=${km.isDeviceLocked} deviceSecure=${km.isDeviceSecure}"
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        overlay = RippleOverlay(this)
        startInForeground()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        // NOT_EXPORTED is fine for protected system broadcasts and satisfies Android 14+ rules.
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        EventLog.add(this, "SERVICE", "monitor started; ${snapshot()}")
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "PULSE monitor", NotificationManager.IMPORTANCE_MIN)
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("PULSE monitor running")
            .setContentText("Waiting for the device to be unlocked")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        try { unregisterReceiver(receiver) } catch (_: IllegalArgumentException) { }
        overlay.dismiss("service destroyed")
        running = false
        EventLog.add(this, "SERVICE", "monitor stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "pulse_monitor"
        private const val DEBOUNCE_MS = 1500L
        @Volatile var running = false
    }
}
