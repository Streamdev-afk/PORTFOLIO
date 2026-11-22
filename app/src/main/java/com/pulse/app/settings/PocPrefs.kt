package com.pulse.app.settings

import android.content.Context

/**
 * Phase 1 storage for the sensor origin as NORMALIZED coordinates (0..1 of screen size).
 * Normalized values survive resolution, density and size changes. The default is simply
 * the screen centre-right placeholder; it is NOT a measured sensor position. The user
 * must calibrate it against the real sensor on their own device.
 * (DataStore replaces this in Phase 3.)
 */
object PocPrefs {
    private const val NAME = "pulse_poc"

    fun originX(ctx: Context): Float = prefs(ctx).getFloat("x", 0.95f)
    fun originY(ctx: Context): Float = prefs(ctx).getFloat("y", 0.40f)

    fun setOrigin(ctx: Context, x: Float, y: Float) {
        prefs(ctx).edit().putFloat("x", x.coerceIn(0f, 1f)).putFloat("y", y.coerceIn(0f, 1f)).apply()
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
}
