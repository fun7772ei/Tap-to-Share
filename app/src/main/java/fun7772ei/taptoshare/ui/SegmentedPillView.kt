package fun7772ei.taptoshare.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import fun7772ei.taptoshare.R
import fun7772ei.taptoshare.UiHelpers

class SegmentedPillView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val thumb: View
    private val row: LinearLayout
    private val labels = mutableListOf<TextView>()
    private var selectedIndex = 0
    private var optionCount = 0

    init {
        val pad = UiHelpers.dp(context, 4)
        setPadding(pad, pad, pad, pad)
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = UiHelpers.dpF(context, 16f)
            setColor(UiHelpers.color(context, R.color.surface_subtle))
        }

        thumb = View(context).apply {
            elevation = UiHelpers.dpF(context, 1.5f)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = UiHelpers.dpF(context, 12f)
                setColor(UiHelpers.color(context, R.color.surface_card_elevated))
            }
        }
        addView(thumb, LayoutParams(0, 0))

        row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            elevation = UiHelpers.dpF(context, 4f)
        }
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun setOptions(options: List<String>, initialIndex: Int, onSelected: (Int) -> Unit) {
        optionCount = options.size
        row.removeAllViews()
        labels.clear()

        options.forEachIndexed { idx, labelText ->
            val tv = TextView(context).apply {
                text = labelText
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(0, UiHelpers.dp(context, 9), 0, UiHelpers.dp(context, 9))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    HapticFeedbackEngine.playUiTap(context)
                    select(idx, animate = true)
                    onSelected(idx)
                }
            }
            row.addView(tv, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            labels.add(tv)
        }
        select(initialIndex.coerceIn(0, maxOf(0, optionCount - 1)), animate = false)
    }

    fun select(index: Int, animate: Boolean) {
        if (optionCount <= 0) return
        val clamped = index.coerceIn(0, optionCount - 1)
        selectedIndex = clamped
        val activeCol = UiHelpers.color(context, R.color.text_primary)
        val inactiveCol = UiHelpers.color(context, R.color.text_secondary)
        labels.forEachIndexed { i, tv ->
            val sel = i == clamped
            tv.setTextColor(if (sel) activeCol else inactiveCol)
            tv.setTypeface(null, if (sel) Typeface.BOLD else Typeface.NORMAL)
        }
        updateThumb(animate)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (optionCount > 0) updateThumb(false)
    }

    private fun updateThumb(animate: Boolean) {
        if (optionCount <= 0 || row.width <= 0 || row.height <= 0) return
        val w = row.width.toFloat() / optionCount
        val targetW = w.toInt()
        val targetH = row.height
        val lp = thumb.layoutParams as LayoutParams
        if (lp.width != targetW || lp.height != targetH) {
            lp.width = targetW
            lp.height = targetH
            thumb.layoutParams = lp
        }
        val maxX = (row.width - targetW).toFloat().coerceAtLeast(0f)
        val tx = (selectedIndex * w).coerceIn(0f, maxX)
        thumb.animate().cancel()
        if (animate && isLaidOut) {
            thumb.animate()
                .translationX(tx)
                .setDuration(240L)
                .setInterpolator(PathInterpolator(0.2f, 0f, 0f, 1f))
                .start()
        } else {
            thumb.translationX = tx
        }
    }
}
