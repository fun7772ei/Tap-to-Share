package fun7772ei.taptoshare.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import fun7772ei.taptoshare.AppSettings

object HapticFeedbackEngine {

    private fun getVibrator(context: Context): Vibrator? {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
        }.getOrNull()
    }

    private fun isEnabled(context: Context): Boolean = AppSettings(context).vibrateOnComplete

    fun playUiTap(context: Context) {
        if (!isEnabled(context)) return
        val vibrator = getVibrator(context) ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_TICK)
            ) {
                val comp = VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.35f)
                    .compose()
                vibrator.vibrate(comp)
            } else {
                vibrator.vibrate(VibrationEffect.createOneShot(12L, 80))
            }
        }
    }

    fun playReadyTick(context: Context) {
        if (!isEnabled(context)) return
        val vibrator = getVibrator(context) ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_TICK)
            ) {
                val comp = VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.55f)
                    .compose()
                vibrator.vibrate(comp)
            } else {
                vibrator.vibrate(VibrationEffect.createOneShot(16L, 100))
            }
        }
    }

    fun playNfcContact(context: Context) {
        if (!isEnabled(context)) return
        val vibrator = getVibrator(context) ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                vibrator.areAllPrimitivesSupported(
                    VibrationEffect.Composition.PRIMITIVE_THUD,
                    VibrationEffect.Composition.PRIMITIVE_QUICK_RISE
                )
            ) {
                val comp = VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 0.85f)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, 0.65f, 30)
                    .compose()
                vibrator.vibrate(comp)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK)
            ) {
                val comp = VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.9f)
                    .compose()
                vibrator.vibrate(comp)
            } else {
                vibrator.vibrate(VibrationEffect.createOneShot(36L, 210))
            }
        }
    }

    fun playTransferComplete(context: Context) {
        if (!isEnabled(context)) return
        val vibrator = getVibrator(context) ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                vibrator.areAllPrimitivesSupported(
                    VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
                    VibrationEffect.Composition.PRIMITIVE_CLICK
                )
            ) {
                val comp = VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.65f)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1.0f, 60)
                    .compose()
                vibrator.vibrate(comp)
            } else {
                vibrator.vibrate(
                    VibrationEffect.createWaveform(
                        longArrayOf(0L, 22L, 52L, 38L),
                        intArrayOf(0, 145, 0, 235),
                        -1
                    )
                )
            }
        }
    }

    fun playError(context: Context) {
        if (!isEnabled(context)) return
        val vibrator = getVibrator(context) ?: return
        runCatching {
            vibrator.vibrate(
                VibrationEffect.createWaveform(
                    longArrayOf(0L, 36L, 55L, 42L),
                    intArrayOf(0, 190, 0, 190),
                    -1
                )
            )
        }
    }
}
