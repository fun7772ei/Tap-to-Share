package fun7772ei.taptoshare

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.location.LocationManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.shape.CornerFamily
import com.google.android.material.shape.ShapeAppearanceModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.exp

data class BottomSheetScaffold(
    val backdrop: FrameLayout,
    val card: MaterialCardView,
    val contentLayout: LinearLayout
)

class SpringInterpolator(
    private val damping: Float = 0.56f,
    private val frequency: Float = 1.65f
) : Interpolator {
    override fun getInterpolation(input: Float): Float {
        if (input <= 0f) return 0f
        if (input >= 1f) return 1f
        val decay = exp((-damping * input * 7.0).toDouble())
        val osc = cos(frequency * Math.PI * 2.0 * input)
        return (1.0 - decay * osc).toFloat()
    }
}

object UiHelpers {

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    fun dpF(context: Context, value: Float): Float =
        value * context.resources.displayMetrics.density

    fun color(context: Context, @ColorRes resId: Int): Int =
        ContextCompat.getColor(context, resId)

    fun isNightMode(context: Context): Boolean {
        val uiMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return uiMode == Configuration.UI_MODE_NIGHT_YES
    }

    fun tintIcon(context: Context, @DrawableRes resId: Int, tintColor: Int): Drawable? {
        val raw = ContextCompat.getDrawable(context, resId)?.mutate() ?: return null
        DrawableCompat.setTint(raw, tintColor)
        return raw
    }

    fun createGlassDrawable(
        fillColor: Int,
        strokeColor: Int,
        radiusPx: Float,
        strokeWidthPx: Int = 2
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusPx
        setColor(fillColor)
        if (strokeWidthPx > 0) {
            setStroke(strokeWidthPx, strokeColor)
        }
    }

    fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024L -> "$bytes B"
            bytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
            bytes < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
            else -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }

    fun formatRelativeTime(timestampMs: Long): String {
        if (timestampMs <= 0L) return ""
        val now = System.currentTimeMillis()
        val diff = maxOf(0L, now - timestampMs)
        return when {
            diff < 60_000L -> "たった今"
            diff < 3_600_000L -> "${diff / 60_000L}分前"
            diff < 24 * 3_600_000L -> "${diff / 3_600_000L}時間前"
            else -> {
                val calNow = Calendar.getInstance()
                val calThen = Calendar.getInstance().apply { timeInMillis = timestampMs }
                val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
                if (calThen.get(Calendar.YEAR) == yesterday.get(Calendar.YEAR) &&
                    calThen.get(Calendar.DAY_OF_YEAR) == yesterday.get(Calendar.DAY_OF_YEAR)
                ) {
                    "昨日 " + SimpleDateFormat("HH:mm", Locale.US).format(Date(timestampMs))
                } else if (calThen.get(Calendar.YEAR) == calNow.get(Calendar.YEAR)) {
                    SimpleDateFormat("MM/dd HH:mm", Locale.US).format(Date(timestampMs))
                } else {
                    SimpleDateFormat("yyyy/MM/dd", Locale.US).format(Date(timestampMs))
                }
            }
        }
    }

    fun configureEdgeToEdgeBars(activity: AppCompatActivity, darkBackground: Boolean? = null) {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        val useLightIcons = darkBackground ?: isNightMode(activity)
        val controller = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
        controller.isAppearanceLightStatusBars = !useLightIcons
        controller.isAppearanceLightNavigationBars = !useLightIcons
        runCatching {
            activity.window.isNavigationBarContrastEnforced = false
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                @Suppress("DEPRECATION")
                activity.window.isStatusBarContrastEnforced = false
            }
        }
    }

    fun applyWindowBlur(window: Window, enabled: Boolean, radiusPx: Int = 56) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        runCatching {
            if (enabled) {
                window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                window.attributes = window.attributes.apply {
                    blurBehindRadius = radiusPx
                }
                window.setBackgroundBlurRadius(radiusPx)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                window.attributes = window.attributes.apply {
                    blurBehindRadius = 0
                }
                window.setBackgroundBlurRadius(0)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun applyPressScale(view: View, pressedScale: Float = 0.965f) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().cancel()
                    v.animate()
                        .scaleX(pressedScale)
                        .scaleY(pressedScale)
                        .setDuration(90L)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().cancel()
                    v.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(180L)
                        .setInterpolator(DecelerateInterpolator(1.6f))
                        .start()
                }
            }
            false
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun applyRowPressFeedback(view: View, pressedAlpha: Float = 0.72f) {
        val tv = TypedValue()
        if (view.context.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true) && tv.resourceId != 0) {
            view.foreground = ContextCompat.getDrawable(view.context, tv.resourceId)
        }
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().cancel()
                    v.animate()
                        .alpha(pressedAlpha)
                        .setDuration(75L)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().cancel()
                    v.animate()
                        .alpha(1f)
                        .setDuration(150L)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                }
            }
            false
        }
    }

    fun canReadUri(context: Context, uri: Uri): Boolean {
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { true } ?: false
        }.getOrDefault(false)
    }

    fun isWifiEnabled(context: Context): Boolean {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        return wm?.isWifiEnabled != false
    }

    fun openWifiPanel(context: Context) {
        val panelIntent = Intent(Settings.Panel.ACTION_WIFI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val launched = runCatching {
            context.startActivity(panelIntent)
            true
        }.getOrDefault(false)
        if (!launched) {
            runCatching {
                context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    fun isLocationRequiredAndDisabled(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return false
        val lm = context.applicationContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return false
        return !lm.isLocationEnabled
    }

    fun openLocationSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun vibrateCompletion(context: Context) {
        runCatching {
            val effect = VibrationEffect.createOneShot(45L, VibrationEffect.DEFAULT_AMPLITUDE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                val v = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                v.vibrate(effect)
            }
        }
    }

    fun missingWifiPermissions(context: Context): Array<String> {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        } else {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
        }
        return needed.toTypedArray()
    }

    fun missingAllRecommendedPermissions(context: Context): Array<String> {
        val needed = missingWifiPermissions(context).toMutableList()
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        return needed.toTypedArray()
    }

    fun queryUriMeta(context: Context, uri: Uri): Triple<String, Long, String> {
        var name = "shared_file.bin"
        var size = 0L
        val mime = context.contentResolver.getType(uri)?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nIdx >= 0 && !cursor.isNull(nIdx)) name = cursor.getString(nIdx)
                    if (sIdx >= 0 && !cursor.isNull(sIdx)) size = cursor.getLong(sIdx)
                }
            }
        }
        if (size <= 0L) {
            runCatching {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                    if (afd.length > 0L) size = afd.length
                }
            }
        }
        return Triple(name, size, mime)
    }

    fun buildBottomSheetScaffold(
        activity: AppCompatActivity,
        onBackdropTap: () -> Unit
    ): BottomSheetScaffold {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)

        val backdrop = FrameLayout(activity).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { onBackdropTap() }
        }

        val radiusPx = dp(activity, 24).toFloat()
        val sheetShape = ShapeAppearanceModel.builder()
            .setTopLeftCorner(CornerFamily.ROUNDED, radiusPx)
            .setTopRightCorner(CornerFamily.ROUNDED, radiusPx)
            .build()

        val surfaceColor = MaterialColors.getColor(
            activity,
            com.google.android.material.R.attr.colorSurfaceContainerLow,
            Color.parseColor("#1E1E24")
        )

        val card = MaterialCardView(activity).apply {
            shapeAppearanceModel = sheetShape
            setCardBackgroundColor(surfaceColor)
            cardElevation = dp(activity, 12).toFloat()
            strokeWidth = 0
            isClickable = true
            isFocusable = true
        }

        val scroll = ScrollView(activity).apply {
            isFillViewport = true
        }

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val hPad = dp(activity, 20)
            val tPad = dp(activity, 12)
            setPadding(hPad, tPad, hPad, dp(activity, 20))
        }

        val handleColor = MaterialColors.getColor(
            activity,
            com.google.android.material.R.attr.colorOnSurfaceVariant,
            Color.GRAY
        )
        val handle = View(activity).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(activity, 3).toFloat()
                setColor(handleColor)
                alpha = 90
            }
        }
        content.addView(handle, LinearLayout.LayoutParams(dp(activity, 40), dp(activity, 4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(activity, 14)
        })

        scroll.addView(content)
        card.addView(scroll)

        val cardLp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM
        )
        backdrop.addView(card, cardLp)

        ViewCompat.setOnApplyWindowInsetsListener(backdrop) { _, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            card.setContentPadding(0, 0, 0, sys.bottom)
            backdrop.setPadding(0, sys.top + dp(activity, 48), 0, 0)
            insets
        }

        activity.setContentView(backdrop)
        return BottomSheetScaffold(backdrop, card, content)
    }
}
