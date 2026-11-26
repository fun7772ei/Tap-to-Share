package fun7772ei.taptoshare.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import fun7772ei.taptoshare.HistoryEntry
import fun7772ei.taptoshare.R
import fun7772ei.taptoshare.UiHelpers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.Calendar

enum class HistoryFilter(val label: String) {
    ALL("すべて"),
    RECEIVE("受信"),
    SEND("送信"),
    MEDIA("メディア"),
    TEXT_URL("テキスト"),
    FILES("ファイル")
}

interface HistoryTabCallbacks {
    fun onHistoryOpen(entry: HistoryEntry)
    fun onHistoryResend(entry: HistoryEntry)
    fun onHistoryDelete(entry: HistoryEntry)
    fun onHistoryClearAll()
}

private sealed class HistoryListItem {
    data class Section(val title: String) : HistoryListItem()
    data class Entry(
        val entry: HistoryEntry,
        val isFirstInGroup: Boolean,
        val isLastInGroup: Boolean
    ) : HistoryListItem()
}

/**
 * Clean neutral Chinese-OEM inset timeline list with monochrome filter pills
 * and continuous rounded white/charcoal cards separated by hairline dividers.
 */
class HistoryTabView(
    context: Context,
    private val uiScope: CoroutineScope,
    private val callbacks: HistoryTabCallbacks
) : LinearLayout(context) {

    private var allItems: List<HistoryEntry> = emptyList()
    private var activeFilter: HistoryFilter = HistoryFilter.ALL

    private val filterViews = mutableMapOf<HistoryFilter, TextView>()
    private val clearAllBtn: TextView
    private val recyclerView: RecyclerView
    private val emptyStateView: LinearLayout
    private val adapter = HistoryTimelineAdapter()

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.TRANSPARENT)

        val hp = UiHelpers.dp(context, 18)

        // Header
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(hp, UiHelpers.dp(context, 18), hp, UiHelpers.dp(context, 10))
        }
        val title = TextView(context).apply {
            text = "履歴"
            textSize = 27f
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = -0.025f
            setTextColor(UiHelpers.color(context, R.color.text_primary))
        }
        clearAllBtn = TextView(context).apply {
            text = "消去"
            textSize = 13.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(UiHelpers.color(context, R.color.text_secondary))
            setPadding(UiHelpers.dp(context, 12), UiHelpers.dp(context, 6), UiHelpers.dp(context, 4), UiHelpers.dp(context, 6))
            setOnClickListener {
                HapticFeedbackEngine.playUiTap(context)
                MaterialAlertDialogBuilder(context)
                    .setTitle("履歴を消去")
                    .setMessage("送受信履歴をすべて削除しますか？")
                    .setPositiveButton("削除") { _, _ -> callbacks.onHistoryClearAll() }
                    .setNegativeButton("キャンセル", null)
                    .show()
            }
        }
        header.addView(title, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        header.addView(clearAllBtn)
        addView(header)

        // Monochrome filter pills
        val filterScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            setPadding(hp, UiHelpers.dp(context, 2), hp, UiHelpers.dp(context, 10))
        }
        val filterRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }
        HistoryFilter.entries.forEach { filter ->
            val pill = TextView(context).apply {
                text = filter.label
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                val php = UiHelpers.dp(context, 14)
                val pvp = UiHelpers.dp(context, 7)
                setPadding(php, pvp, php, pvp)
                UiHelpers.applyPressScale(this, 0.94f)
                setOnClickListener {
                    HapticFeedbackEngine.playUiTap(context)
                    activeFilter = filter
                    refreshFilterPills()
                    applyFilter()
                }
            }
            filterViews[filter] = pill
            filterRow.addView(pill, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginEnd = UiHelpers.dp(context, 8)
            })
        }
        filterScroll.addView(filterRow)
        addView(filterScroll)

        // Content stack
        val host = FrameLayout(context)
        recyclerView = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            clipToPadding = false
            setPadding(hp, UiHelpers.dp(context, 2), hp, UiHelpers.dp(context, 114))
            adapter = this@HistoryTabView.adapter
        }
        host.addView(recyclerView, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        emptyStateView = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            visibility = GONE
            setPadding(UiHelpers.dp(context, 32), UiHelpers.dp(context, 48), UiHelpers.dp(context, 32), UiHelpers.dp(context, 110))
        }
        val emptyIconWrap = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(UiHelpers.color(context, R.color.surface_card))
            }
        }
        val emptyIcon = ImageView(context).apply {
            setImageDrawable(UiHelpers.tintIcon(context, R.drawable.ic_nav_history, UiHelpers.color(context, R.color.text_tertiary)))
        }
        emptyIconWrap.addView(emptyIcon, FrameLayout.LayoutParams(UiHelpers.dp(context, 26), UiHelpers.dp(context, 26), Gravity.CENTER))
        emptyStateView.addView(emptyIconWrap, LayoutParams(UiHelpers.dp(context, 60), UiHelpers.dp(context, 60)).apply {
            bottomMargin = UiHelpers.dp(context, 14)
        })

        val emptyTitle = TextView(context).apply {
            text = "履歴はありません"
            textSize = 15.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(UiHelpers.color(context, R.color.text_secondary))
            gravity = Gravity.CENTER
        }
        emptyStateView.addView(emptyTitle, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        host.addView(emptyStateView, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        addView(host, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        refreshFilterPills()
    }

    private fun refreshFilterPills() {
        val fillActive = UiHelpers.color(context, R.color.button_monochrome_fill)
        val textActive = UiHelpers.color(context, R.color.button_monochrome_text)
        val fillInactive = UiHelpers.color(context, R.color.surface_card)
        val textInactive = UiHelpers.color(context, R.color.text_secondary)

        filterViews.forEach { (filter, tv) ->
            val selected = filter == activeFilter
            tv.setTextColor(if (selected) textActive else textInactive)
            tv.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = UiHelpers.dpF(context, 999f)
                setColor(if (selected) fillActive else fillInactive)
            }
        }
    }

    fun submitHistory(items: List<HistoryEntry>) {
        allItems = items
        clearAllBtn.visibility = if (items.isEmpty()) GONE else VISIBLE
        applyFilter()
    }

    private fun applyFilter() {
        val filtered = allItems.filter { entry ->
            when (activeFilter) {
                HistoryFilter.ALL -> true
                HistoryFilter.RECEIVE -> entry.direction == "RECEIVE"
                HistoryFilter.SEND -> entry.direction == "SEND"
                HistoryFilter.MEDIA -> entry.category == "IMAGE" || entry.category == "VIDEO"
                HistoryFilter.TEXT_URL -> entry.category == "URL" || entry.category == "TEXT"
                HistoryFilter.FILES -> entry.category == "FILE"
            }
        }
        val grouped = buildGroupedTimeline(filtered)
        adapter.submitList(grouped)
        emptyStateView.visibility = if (grouped.isEmpty()) VISIBLE else GONE
        recyclerView.visibility = if (grouped.isEmpty()) GONE else VISIBLE
    }

    private fun buildGroupedTimeline(entries: List<HistoryEntry>): List<HistoryListItem> {
        if (entries.isEmpty()) return emptyList()
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val startOfToday = cal.timeInMillis
        val startOfYesterday = startOfToday - 86_400_000L

        val buckets = linkedMapOf<String, MutableList<HistoryEntry>>()
        for (entry in entries) {
            val section = when {
                entry.timestampMs >= startOfToday -> "今日"
                entry.timestampMs >= startOfYesterday -> "昨日"
                else -> "以前"
            }
            buckets.getOrPut(section) { mutableListOf() }.add(entry)
        }

        val out = mutableListOf<HistoryListItem>()
        buckets.forEach { (section, list) ->
            out.add(HistoryListItem.Section(section))
            list.forEachIndexed { idx, entry ->
                out.add(
                    HistoryListItem.Entry(
                        entry = entry,
                        isFirstInGroup = idx == 0,
                        isLastInGroup = idx == list.lastIndex
                    )
                )
            }
        }
        return out
    }

    private inner class HistoryTimelineAdapter : ListAdapter<HistoryListItem, RecyclerView.ViewHolder>(
        object : DiffUtil.ItemCallback<HistoryListItem>() {
            override fun areItemsTheSame(oldItem: HistoryListItem, newItem: HistoryListItem): Boolean {
                return when {
                    oldItem is HistoryListItem.Section && newItem is HistoryListItem.Section -> oldItem.title == newItem.title
                    oldItem is HistoryListItem.Entry && newItem is HistoryListItem.Entry -> oldItem.entry.id == newItem.entry.id
                    else -> false
                }
            }

            override fun areContentsTheSame(oldItem: HistoryListItem, newItem: HistoryListItem): Boolean = oldItem == newItem
        }
    ) {
        private val TYPE_SECTION = 0
        private val TYPE_ENTRY = 1

        override fun getItemViewType(position: Int): Int = when (getItem(position)) {
            is HistoryListItem.Section -> TYPE_SECTION
            is HistoryListItem.Entry -> TYPE_ENTRY
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == TYPE_SECTION) {
                val tv = TextView(parent.context).apply {
                    textSize = 13f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(UiHelpers.color(parent.context, R.color.text_secondary))
                    setPadding(UiHelpers.dp(parent.context, 6), UiHelpers.dp(parent.context, 14), 0, UiHelpers.dp(parent.context, 6))
                    layoutParams = RecyclerView.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                }
                object : RecyclerView.ViewHolder(tv) {}
            } else {
                createEntryViewHolder(parent)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = getItem(position)) {
                is HistoryListItem.Section -> (holder.itemView as TextView).text = item.title
                is HistoryListItem.Entry -> bindEntry(holder as EntryVH, item)
            }
        }

        private inner class EntryVH(
            root: LinearLayout,
            val cardBody: LinearLayout,
            val divider: View,
            val thumbImage: ImageView,
            val titleText: TextView,
            val metaText: TextView,
            val actionBtn: ImageButton
        ) : RecyclerView.ViewHolder(root)

        private fun createEntryViewHolder(parent: ViewGroup): EntryVH {
            val ctx = parent.context
            val root = LinearLayout(ctx).apply {
                orientation = VERTICAL
                layoutParams = RecyclerView.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            }

            val cardBody = LinearLayout(ctx).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val hp = UiHelpers.dp(ctx, 16)
                val vp = UiHelpers.dp(ctx, 13)
                setPadding(hp, vp, UiHelpers.dp(ctx, 8), vp)
                isClickable = true
                isFocusable = true
            }

            val thumbFrame = FrameLayout(ctx).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = UiHelpers.dpF(ctx, 11f)
                    setColor(UiHelpers.color(ctx, R.color.surface_subtle))
                }
                clipToOutline = true
            }
            val thumbImg = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            thumbFrame.addView(thumbImg, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            cardBody.addView(thumbFrame, LayoutParams(UiHelpers.dp(ctx, 42), UiHelpers.dp(ctx, 42)).apply {
                marginEnd = UiHelpers.dp(ctx, 12)
            })

            val col = LinearLayout(ctx).apply { orientation = VERTICAL }
            val titleTv = TextView(ctx).apply {
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(UiHelpers.color(ctx, R.color.text_primary))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            val metaTv = TextView(ctx).apply {
                textSize = 12.5f
                setTextColor(UiHelpers.color(ctx, R.color.text_secondary))
                fontFeatureSettings = "tnum"
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, UiHelpers.dp(ctx, 2), 0, 0)
            }
            col.addView(titleTv)
            col.addView(metaTv)
            cardBody.addView(col, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

            val actionBtn = ImageButton(ctx).apply {
                background = null
                setImageDrawable(UiHelpers.tintIcon(ctx, R.drawable.ic_send_up, UiHelpers.color(ctx, R.color.text_secondary)))
                val p = UiHelpers.dp(ctx, 10)
                setPadding(p, p, p, p)
                contentDescription = "再送信"
            }
            cardBody.addView(actionBtn, LayoutParams(UiHelpers.dp(ctx, 40), UiHelpers.dp(ctx, 40)))

            val divider = View(ctx).apply {
                setBackgroundColor(UiHelpers.color(ctx, R.color.divider_inset))
            }

            root.addView(cardBody, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            root.addView(divider, LayoutParams(LayoutParams.MATCH_PARENT, 1).apply {
                marginStart = UiHelpers.dp(ctx, 70)
                marginEnd = UiHelpers.dp(ctx, 16)
            })

            return EntryVH(root, cardBody, divider, thumbImg, titleTv, metaTv, actionBtn)
        }

        private fun bindEntry(holder: EntryVH, item: HistoryListItem.Entry) {
            val ctx = holder.itemView.context
            val entry = item.entry
            val outerR = UiHelpers.dpF(ctx, 22f)
            val topR = if (item.isFirstInGroup) outerR else 0f
            val bottomR = if (item.isLastInGroup) outerR else 0f

            holder.cardBody.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadii = floatArrayOf(topR, topR, topR, topR, bottomR, bottomR, bottomR, bottomR)
                setColor(UiHelpers.color(ctx, R.color.surface_card))
            }
            holder.divider.visibility = if (item.isLastInGroup) GONE else VISIBLE

            holder.titleText.text = entry.title
            val dirLabel = if (entry.direction == "SEND") "送信" else "受信"
            val parts = buildList {
                add(dirLabel)
                if (entry.totalBytes > 0L) add(UiHelpers.formatBytes(entry.totalBytes))
                if (entry.speedMBps > 0.1) add("${"%.1f".format(entry.speedMBps)} MB/s")
                add(UiHelpers.formatRelativeTime(entry.timestampMs))
            }
            holder.metaText.text = parts.joinToString(" · ")

            val fallbackRes = when (entry.category) {
                "IMAGE" -> R.drawable.ic_photo_library
                "VIDEO" -> R.drawable.ic_video
                "URL" -> R.drawable.ic_text_link
                "TEXT" -> R.drawable.ic_clipboard
                else -> R.drawable.ic_file_doc
            }
            holder.thumbImage.setPadding(UiHelpers.dp(ctx, 10), UiHelpers.dp(ctx, 10), UiHelpers.dp(ctx, 10), UiHelpers.dp(ctx, 10))
            holder.thumbImage.setImageDrawable(UiHelpers.tintIcon(ctx, fallbackRes, UiHelpers.color(ctx, R.color.text_primary)))
            holder.thumbImage.tag = entry.id

            if ((entry.category == "IMAGE" || entry.category == "VIDEO") && !entry.uriString.isNullOrBlank()) {
                uiScope.launch {
                    val bmp = ThumbnailLoader.loadThumbnail(ctx, Uri.parse(entry.uriString), entry.mimeType ?: "*/*", 128)
                    if (bmp != null && holder.thumbImage.tag == entry.id) {
                        holder.thumbImage.setPadding(0, 0, 0, 0)
                        holder.thumbImage.setImageBitmap(bmp)
                    }
                }
            }

            holder.cardBody.setOnClickListener {
                HapticFeedbackEngine.playUiTap(ctx)
                callbacks.onHistoryOpen(entry)
            }
            holder.cardBody.setOnLongClickListener {
                HapticFeedbackEngine.playUiTap(ctx)
                MaterialAlertDialogBuilder(ctx)
                    .setTitle(entry.title)
                    .setItems(arrayOf("開く", "もう一度送信", "履歴から削除")) { _, which ->
                        when (which) {
                            0 -> callbacks.onHistoryOpen(entry)
                            1 -> callbacks.onHistoryResend(entry)
                            2 -> callbacks.onHistoryDelete(entry)
                        }
                    }
                    .show()
                true
            }
            holder.actionBtn.setOnClickListener {
                HapticFeedbackEngine.playUiTap(ctx)
                callbacks.onHistoryResend(entry)
            }
        }
    }
}
