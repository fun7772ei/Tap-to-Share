package fun7772ei.taptoshare.ui.animation

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.transition.ChangeBounds
import android.transition.Fade
import android.transition.TransitionManager
import android.transition.TransitionSet
import android.util.AttributeSet
import android.view.Gravity
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import fun7772ei.taptoshare.R
import fun7772ei.taptoshare.UiHelpers

enum class PillTone {
    READY,
    CONNECTING,
    TRANSFERRING,
    COMPLETE,
    ERROR
}

class DynamicStatusPillView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val iconView: ImageView
    private val labelView: TextView
    private val routeBadge: TextView

    private var currentTone: PillTone = PillTone.READY
    private var pulseAnimator: ValueAnimator? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val hp = UiHelpers.dp(context, 13)
        val vp = UiHelpers.dp(context, 7)
        setPadding(hp, vp, hp, vp)
        elevation = UiHelpers.dpF(context, 10f)

        iconView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        addView(iconView, LayoutParams(UiHelpers.dp(context, 16), UiHelpers.dp(context, 16)).apply {
            marginEnd = UiHelpers.dp(context, 7)
        })

        labelView = TextView(context).apply {
            textSize = 12.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            maxLines = 1
            fontFeatureSettings = "tnum"
        }
        addView(labelView, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        routeBadge = TextView(context).apply {
            textSize = 10.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            val bhp = UiHelpers.dp(context, 6)
            val bvp = UiHelpers.dp(context, 2)
            setPadding(bhp, bvp, bhp, bvp)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = UiHelpers.dpF(context, 999f)
                setColor(Color.parseColor("#33FFFFFF"))
            }
            visibility = GONE
        }
        addView(routeBadge, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = UiHelpers.dp(context, 7)
        })

        applyToneBackground(PillTone.READY)
    }

    fun setStatus(
        text: String,
        tone: PillTone,
        routeTag: String? = null,
        @DrawableRes iconRes: Int = defaultIconForTone(tone),
        animateLayout: Boolean = true
    ) {
        if (animateLayout && isLaidOut && parent is ViewGroup) {
            runCatching {
                val tr = TransitionSet().apply {
                    ordering = TransitionSet.ORDERING_TOGETHER
                    addTransition(ChangeBounds().setDuration(220L))
                    addTransition(Fade(Fade.IN).setDuration(180L))
                    interpolator = DecelerateInterpolator()
                }
                TransitionManager.beginDelayedTransition(parent as ViewGroup, tr)
            }
        }

        labelView.text = text
        iconView.setImageDrawable(UiHelpers.tintIcon(context, iconRes, Color.WHITE))

        if (routeTag.isNullOrBlank()) {
            routeBadge.visibility = GONE
        } else {
            routeBadge.text = routeTag
            routeBadge.visibility = VISIBLE
        }

        if (tone != currentTone) {
            currentTone = tone
            applyToneBackground(tone)
        }
        updateIconPulse(tone == PillTone.READY || tone == PillTone.CONNECTING || tone == PillTone.TRANSFERRING)
    }

    private fun applyToneBackground(tone: PillTone) {
        val (startHex, endHex, strokeHex) = when (tone) {
            PillTone.READY -> Triple("#EB1C1C1E", "#EB2A2A2D", "#44FFFFFF")
            PillTone.CONNECTING -> Triple("#EB1F1F22", "#EB323236", "#55FFFFFF")
            PillTone.TRANSFERRING -> Triple("#EB1E1E21", "#EB2C2C30", "#66FFFFFF")
            PillTone.COMPLETE -> Triple("#EB1C1C1E", "#EB2A2A2E", "#55FFFFFF")
            PillTone.ERROR -> Triple("#DC2626", "#EF4444", "#55FECACA")
        }
        background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(Color.parseColor(startHex), Color.parseColor(endHex))
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = UiHelpers.dpF(context, 999f)
            setStroke(UiHelpers.dp(context, 1), Color.parseColor(strokeHex))
        }
    }

    private fun updateIconPulse(active: Boolean) {
        if (active) {
            if (pulseAnimator == null) {
                pulseAnimator = ValueAnimator.ofFloat(1f, 1.18f, 1f).apply {
                    duration = 1350L
                    repeatCount = ValueAnimator.INFINITE
                    addUpdateListener {
                        val s = it.animatedValue as Float
                        iconView.scaleX = s
                        iconView.scaleY = s
                    }
                    start()
                }
            }
        } else {
            pulseAnimator?.cancel()
            pulseAnimator = null
            iconView.scaleX = 1f
            iconView.scaleY = 1f
        }
    }

    override fun onDetachedFromWindow() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        super.onDetachedFromWindow()
    }

    private fun defaultIconForTone(tone: PillTone): Int = when (tone) {
        PillTone.READY -> R.drawable.ic_nfc_waves
        PillTone.CONNECTING -> R.drawable.ic_wifi_signal
        PillTone.TRANSFERRING -> R.drawable.ic_nfc_waves
        PillTone.COMPLETE -> R.drawable.ic_nfc_waves
        PillTone.ERROR -> R.drawable.ic_error_circle
    }
}
