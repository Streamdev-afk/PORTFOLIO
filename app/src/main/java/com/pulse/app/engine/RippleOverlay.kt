package com.pulse.app.engine

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import com.pulse.app.diagnostics.EventLog
import kotlin.math.hypot

/**
 * Shows ONE ripple overlay at a time and always removes it afterwards.
 *
 * Window is TYPE_APPLICATION_OVERLAY, not focusable and not touchable, so taps pass through
 * to whatever is underneath. A watchdog removes the window even if the animator never
 * finishes, so a transparent overlay can never be left behind.
 *
 * Important: this window is only ever added AFTER the system reports the user is present.
 * It is not an attempt to draw above the secure keyguard (Android does not allow that).
 */
class RippleOverlay(context: Context) {
    private val appCtx = context.applicationContext
    private val wm = appCtx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private var view: RippleView? = null
    private val watchdog = Runnable { dismiss("watchdog") }

    /** @param triggerElapsedMs SystemClock.elapsedRealtime() when the trigger event arrived, for latency logging. */
    fun show(nx: Float, ny: Float, durationMs: Long = 900L, triggerElapsedMs: Long = SystemClock.elapsedRealtime()) {
        if (!Settings.canDrawOverlays(appCtx)) {
            EventLog.add(appCtx, "OVERLAY", "BLOCKED: overlay permission not granted")
            return
        }
        dismiss("replace") // guarantees no duplicate windows
        try {
            val v = RippleView(appCtx, nx, ny, durationMs, triggerElapsedMs) { dismiss("finished") }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            wm.addView(v, lp)
            view = v
            EventLog.add(appCtx, "OVERLAY", "addView OK (+${SystemClock.elapsedRealtime() - triggerElapsedMs}ms after trigger)")
            main.postDelayed(watchdog, durationMs + 600L)
            v.start()
        } catch (e: Exception) {
            // Never crash the engine because the overlay failed (e.g. permission revoked mid-flight).
            EventLog.add(appCtx, "OVERLAY", "FAILED: ${e.javaClass.simpleName}: ${e.message}")
            view = null
        }
    }

    fun dismiss(reason: String) {
        main.removeCallbacks(watchdog)
        val v = view ?: return
        view = null
        v.stopAndRelease()
        try {
            wm.removeViewImmediate(v)
            EventLog.add(appCtx, "OVERLAY", "removed ($reason)")
        } catch (e: Exception) {
            EventLog.add(appCtx, "OVERLAY", "remove failed ($reason): ${e.message}")
        }
    }
}

/** Three staggered thin rings expanding from a normalized origin. Paints are preallocated. */
private class RippleView(
    context: Context,
    private val nx: Float,
    private val ny: Float,
    private val durationMs: Long,
    private val triggerElapsedMs: Long,
    private val onDone: () -> Unit
) : View(context) {

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f * resources.displayMetrics.density
    }
    private val core = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private var progress = 0f
    private var firstFrameLogged = false
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = durationMs
        interpolator = DecelerateInterpolator(1.5f)
        addUpdateListener { progress = it.animatedValue as Float; invalidate() }
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) { onDone() }
        })
    }

    fun start() = animator.start()

    fun stopAndRelease() {
        animator.removeAllListeners()
        animator.cancel()
    }

    override fun onDraw(canvas: Canvas) {
        if (!firstFrameLogged) {
            firstFrameLogged = true
            com.pulse.app.diagnostics.EventLog.add(
                context, "OVERLAY", "first frame drawn (+${SystemClock.elapsedRealtime() - triggerElapsedMs}ms after trigger)"
            )
        }
        // Origin is computed from the overlay's own size every frame, so rotation and
        // different resolutions need no pixel constants.
        val cx = nx * width
        val cy = ny * height
        val maxR = hypot(maxOf(cx, width - cx), maxOf(cy, height - cy))
        for (i in 0 until 3) {
            val p = ((progress * 1.4f) - i * 0.18f).coerceIn(0f, 1f)
            if (p <= 0f) continue
            val a = ((1f - p) * 200f).toInt().coerceIn(0, 255)
            ring.color = Color.argb(a, 124, 92, 255)
            canvas.drawCircle(cx, cy, maxR * p, ring)
        }
        if (progress < 0.35f) {
            core.color = Color.argb(((0.35f - progress) / 0.35f * 255f).toInt(), 0, 212, 255)
            canvas.drawCircle(cx, cy, 14f * resources.displayMetrics.density, core)
        }
    }
}
