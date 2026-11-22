package com.pulse.app.diagnostics

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tiny append-only event log used to record what Android really delivers and when.
 * This is the evidence base for the feasibility report: every line is a real observation,
 * nothing is simulated. Stored locally only; capped to avoid unbounded growth.
 */
object EventLog {
    private const val FILE_NAME = "pulse_events.log"
    private const val MAX_LINES = 400
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Volatile var onChanged: (() -> Unit)? = null

    @Synchronized
    fun add(ctx: Context, tag: String, message: String) {
        val line = "${fmt.format(Date())} [${SystemClock.elapsedRealtime() % 1_000_000}ms] $tag: $message"
        try {
            val f = File(ctx.applicationContext.filesDir, FILE_NAME)
            val lines = if (f.exists()) f.readLines().takeLast(MAX_LINES - 1) else emptyList()
            f.writeText((lines + line).joinToString("\n") + "\n")
        } catch (e: Exception) {
            // Logging must never crash the engine; fall back to logcat.
            android.util.Log.e("PULSE", "EventLog write failed: ${e.message}")
        }
        android.util.Log.i("PULSE", line)
        onChanged?.invoke()
    }

    @Synchronized
    fun read(ctx: Context): String {
        val f = File(ctx.applicationContext.filesDir, FILE_NAME)
        return if (f.exists()) f.readLines().asReversed().joinToString("\n") else "(no events yet)"
    }

    @Synchronized
    fun clear(ctx: Context) {
        File(ctx.applicationContext.filesDir, FILE_NAME).delete()
        onChanged?.invoke()
    }
}
