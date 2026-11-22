package com.pulse.app

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.pulse.app.diagnostics.EventLog
import com.pulse.app.engine.RippleOverlay
import com.pulse.app.engine.UnlockMonitorService
import com.pulse.app.settings.PocPrefs

/**
 * Phase 1 feasibility harness. Deliberately plain Views: its only job is to run the real
 * detection + overlay code on a real device and show the evidence. The Compose Studio UI
 * is Phase 3.
 */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var xLabel: TextView
    private lateinit var yLabel: TextView
    private val ui = Handler(Looper.getMainLooper())
    private var previewOverlay: RippleOverlay? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
            setBackgroundColor(Color.parseColor("#08090F"))
        }
        fun text(size: Float, color: String) = TextView(this).apply {
            textSize = size; setTextColor(Color.parseColor(color))
        }
        fun button(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label; isAllCaps = false; setOnClickListener { onClick() }
        }

        root.addView(text(22f, "#FFFFFF").apply { text = "PULSE: Phase 1 feasibility" })
        root.addView(text(12f, "#A1A6B8").apply {
            text = "Trigger = ANY unlock (ACTION_USER_PRESENT). This cannot tell fingerprint from PIN, pattern or face."
        })
        status = text(13f, "#00D4FF").apply { setPadding(0, pad, 0, pad) }
        root.addView(status)

        root.addView(button("1. Grant overlay permission") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        root.addView(button("2. Start monitor") { startMonitor() })
        root.addView(button("Stop monitor") {
            stopService(Intent(this, UnlockMonitorService::class.java)); refresh()
        })
        root.addView(button("Open battery optimisation settings") {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        })

        // Origin calibration (normalized 0..1). Phase 3 replaces this with a drag-to-place screen.
        xLabel = text(13f, "#FFFFFF"); yLabel = text(13f, "#FFFFFF")
        root.addView(xLabel)
        root.addView(seek(PocPrefs.originX(this)) { PocPrefs.setOrigin(this, it, PocPrefs.originY(this)); updateLabels() })
        root.addView(yLabel)
        root.addView(seek(PocPrefs.originY(this)) { PocPrefs.setOrigin(this, PocPrefs.originX(this), it); updateLabels() })
        root.addView(button("Preview ripple now") {
            previewOverlay = (previewOverlay ?: RippleOverlay(this)).also {
                it.show(PocPrefs.originX(this), PocPrefs.originY(this))
            }
        })

        val row = LinearLayout(this).apply { gravity = Gravity.CENTER }
        row.addView(button("Copy log") {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("pulse log", EventLog.read(this)))
            Toast.makeText(this, "Log copied", Toast.LENGTH_SHORT).show()
        })
        row.addView(button("Clear log") { EventLog.clear(this) })
        root.addView(row)

        log = text(11f, "#A1A6B8").apply { typeface = android.graphics.Typeface.MONOSPACE }
        root.addView(log)
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.parseColor("#08090F")); addView(root) })
        updateLabels()
    }

    private fun seek(initial: Float, onChange: (Float) -> Unit) = SeekBar(this).apply {
        max = 1000
        progress = (initial * 1000).toInt()
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) onChange(p / 1000f) }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
    }

    private fun updateLabels() {
        xLabel.text = "Origin X: %.3f".format(PocPrefs.originX(this))
        yLabel.text = "Origin Y: %.3f".format(PocPrefs.originY(this))
    }

    private fun startMonitor() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        try {
            startForegroundService(Intent(this, UnlockMonitorService::class.java))
        } catch (e: Exception) {
            EventLog.add(this, "SERVICE", "start FAILED: ${e.javaClass.simpleName}: ${e.message}")
        }
        ui.postDelayed({ refresh() }, 400)
    }

    private fun refresh() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val notif = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        status.text = """
            Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})
            Overlay permission: ${if (Settings.canDrawOverlays(this)) "granted" else "MISSING"}
            Notification permission: ${if (notif) "granted" else "not granted (service still runs, notice hidden)"}
            Monitor service: ${if (UnlockMonitorService.running) "running" else "stopped"}
            Ignoring battery optimisation: ${pm.isIgnoringBatteryOptimizations(packageName)}
        """.trimIndent()
        log.text = EventLog.read(this)
    }

    override fun onResume() {
        super.onResume()
        EventLog.onChanged = { ui.post { refresh() } }
        refresh()
    }

    override fun onPause() {
        EventLog.onChanged = null
        previewOverlay?.dismiss("activity paused")
        super.onPause()
    }
}
