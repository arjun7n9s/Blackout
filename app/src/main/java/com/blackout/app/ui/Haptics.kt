package com.blackout.app.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Small wrapper so call sites don't repeat the API-level dance. */
object Haptics {

    private fun vibrator(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    /** Fired when a redaction pass lands - the moment bars appear. */
    fun redactionApplied(context: Context) {
        val v = vibrator(context) ?: return
        if (!v.hasVibrator()) return
        runCatching {
            val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
            } else {
                VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE)
            }
            v.vibrate(effect)
        }
    }

    /** Lighter confirmation for a single span toggle. */
    fun tick(context: Context) {
        val v = vibrator(context) ?: return
        if (!v.hasVibrator()) return
        runCatching {
            val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
            } else {
                VibrationEffect.createOneShot(12, 80)
            }
            v.vibrate(effect)
        }
    }
}
