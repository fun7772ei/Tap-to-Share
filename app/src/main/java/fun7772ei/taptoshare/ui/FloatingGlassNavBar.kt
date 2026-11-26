package fun7772ei.taptoshare.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import fun7772ei.taptoshare.R
import fun7772ei.taptoshare.UiHelpers

data class GlassNavItem(
    val id: Int,
    val label: String,
    @param:DrawableRes val iconRes: Int
)

/**
 * Floating pill navigation dock with a smoothly gliding active indicator pill.
 */
class FloatingGlassNavBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val items = mutableListOf<GlassNavItem>()
    private val itemContainers = mutableListOf<LinearLayout>()
    private val itemIcons = mutableListOf<ImageView>()
    private val itemLabels = mutableListOf<TextView>()

    private val pillIndicator: View
    private val row: LinearLayout
    private var selectedIndex = 0
    private var onTabSelected: ((Int) -> Unit)? = null

    init {
        val pad = UiHelpers.dp(context, 6)
        setPadding(pad, pad, pad, pad)
        elevation = UiHelpers.dpF(context, 16f)
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = UiHelpers.dpF(context, 999f)
            setColor(UiHelpers.color(context, R.color.nav_dock_fill))
            setStroke(UiHelpers.dp(context, 1), UiHelpers.color(context, R.color.nav_dock_stroke))
        }
        clipToOutline = true

        pillIndicator = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = UiHelpers.dpF(context, 999f)
                setColor(UiHelpers.color(context, R.color.nav_indicator))
            }
        }
        addView(pillIndicator, LayoutParams(0, LayoutParams.MATCH_PARENT))

        row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun configure(navItems: List<GlassNavItem>, initialId: Int, listener: (Int) -> Unit) {
        items.clear()
        items.addAll(navItems)
        onTabSelected = listener
        row.removeAllViews()
        itemContainers.clear()
        itemIcons.clear()
        itemLabels.clear()

        navItems.forEachIndexed { idx, item ->
            val tab = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                UiHelpers.applyPressScale(this, 0.95f)
                setOnClickListener {
                    HapticFeedbackEngine.playUiTap(context)
                    selectIndex(idx, animate = true)
                    onTabSelected?.invoke(item.id)
                }
            }
            val icon = ImageView(context)
            val label = TextView(context).apply {
                text = item.label
                textSize = 11f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, UiHelpers.dp(context, 2), 0, 0)
            }
            tab.addView(icon, LinearLayout.LayoutParams(UiHelpers.dp(context, 21), UiHelpers.dp(context, 21)))
            tab.addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            row.addView(tab, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            itemContainers.add(tab)
            itemIcons.add(icon)
            itemLabels.add(label)
        }

        val initialIdx = items.indexOfFirst { it.id == initialId }.coerceAtLeast(0)
        selectIndex(initialIdx, animate = false)
    }

    fun selectById(id: Int, animate: Boolean = true) {
        val idx = items.indexOfFirst { it.id == id }
        if (idx >= 0) {
            selectIndex(idx, animate)
        }
    }

    private fun selectIndex(index: Int, animate: Boolean) {
        selectedIndex = index
        val activeColor = UiHelpers.color(context, R.color.text_primary)
        val inactiveColor = UiHelpers.color(context, R.color.text_secondary)

        items.forEachIndexed { i, item ->
            val selected = i == index
            val c = if (selected) activeColor else inactiveColor
            itemIcons[i].setImageDrawable(UiHelpers.tintIcon(context, item.iconRes, c))
            itemLabels[i].setTextColor(c)
            if (animate && selected) {
                itemIcons[i].animate().cancel()
                itemIcons[i].animate()
                    .scaleX(1.08f)
                    .scaleY(1.08f)
                    .setDuration(110L)
                    .setInterpolator(DecelerateInterpolator())
                    .withEndAction {
                        itemIcons[i].animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(170L)
                            .setInterpolator(DecelerateInterpolator())
                            .start()
                    }
                    .start()
            } else {
                itemIcons[i].animate().cancel()
                itemIcons[i].scaleX = 1f
                itemIcons[i].scaleY = 1f
            }
        }
        positionIndicator(animate)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (items.isNotEmpty()) {
            positionIndicator(animate = false)
        }
    }

    private fun positionIndicator(animate: Boolean) {
        if (items.isEmpty() || row.width <= 0) return
        val tabW = row.width.toFloat() / items.size
        val targetW = tabW.toInt()
        val lp = pillIndicator.layoutParams as LayoutParams
        if (lp.width != targetW) {
            lp.width = targetW
            pillIndicator.layoutParams = lp
        }
        val maxX = (row.width - targetW).toFloat().coerceAtLeast(0f)
        val targetX = (selectedIndex * tabW).coerceIn(0f, maxX)
        pillIndicator.animate().cancel()
        if (animate && isLaidOut) {
            pillIndicator.animate()
                .translationX(targetX)
                .setDuration(260L)
                .setInterpolator(PathInterpolator(0.2f, 0f, 0f, 1f))
                .start()
        } else {
            pillIndicator.translationX = targetX
        }
    }
}
