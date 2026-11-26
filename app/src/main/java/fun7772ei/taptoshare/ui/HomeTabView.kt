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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.card.MaterialCardView
import fun7772ei.taptoshare.AppSettings
import fun7772ei.taptoshare.HistoryEntry
import fun7772ei.taptoshare.R
import fun7772ei.taptoshare.UiHelpers
import fun7772ei.taptoshare.WifiSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

sealed class ClipboardPreview {
    data object Empty : ClipboardPreview()
    data class Url(val url: String) : ClipboardPreview()
    data class Text(val text: String) : ClipboardPreview()
    data class ContentUri(val uri: Uri, val displayLabel: String) : ClipboardPreview()
}

interface HomeTabCallbacks {
    fun onPlaySendDemo()
    fun onPlayReceiveDemo()
    fun onPickPhotosAndVideos()
    fun onPickFiles()
    fun onPickFolder()
    fun onComposeTextOrUrl()
    fun onSendClipboard()
    fun onSeeAllHistory()
    fun onHistoryClick(entry: HistoryEntry)
    fun onOpenNfcSettings()
    fun onOpenWifiPanel()
}

/**
 * Clean neutral Home screen:
 * - Minimal title header + compact status capsule
 * - Single clean device/connection card
 * - Contextual clipboard quick-send row (visible only when populated)
 * - Clean 2x2 action grid (icon + title)
 * - Quiet inset card for recent transfers
 */
class HomeTabView(
    context: Context,
    private val uiScope: CoroutineScope,
    private val settings: AppSettings,
    private val callbacks: HomeTabCallbacks
) : ScrollView(context) {

    private val nfcCapsule: LinearLayout
    private val nfcDot: View
    private val nfcLabel: TextView

    private val deviceNameText: TextView
    private val connectionText: TextView

    private val clipboardCard: MaterialCardView
    private val clipboardIcon: ImageView
    private val clipboardTitle: TextView
    private val clipboardSubtitle: TextView

    private val recentCardContainer: LinearLayout
    private val recentListContainer: LinearLayout
    private val recentEmptyText: TextView

    init {
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        clipChildren = false
        clipToPadding = false
        setBackgroundColor(Color.TRANSPARENT)

        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            val hp = UiHelpers.dp(context, 18)
            setPadding(hp, UiHelpers.dp(context, 18), hp, UiHelpers.dp(context, 114))
        }

        // 1. Minimal OEM header row
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        val titleText = TextView(context).apply {
            text = "Tap to Share"
            textSize = 27f
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = -0.025f
            setTextColor(UiHelpers.color(context, R.color.text_primary))
        }
        headerRow.addView(titleText, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        nfcCapsule = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val chp = UiHelpers.dp(context, 11)
            val cvp = UiHelpers.dp(context, 6)
            setPadding(chp, cvp, chp, cvp)
            isClickable = true
            isFocusable = true
            UiHelpers.applyPressScale(this, 0.95f)
            setOnClickListener {
                HapticFeedbackEngine.playUiTap(context)
                callbacks.onOpenNfcSettings()
            }
        }
        nfcDot = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(UiHelpers.color(context, R.color.accent_emerald))
            }
        }
        nfcCapsule.addView(nfcDot, LinearLayout.LayoutParams(UiHelpers.dp(context, 7), UiHelpers.dp(context, 7)).apply {
            marginEnd = UiHelpers.dp(context, 6)
        })
        nfcLabel = TextView(context).apply {
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
        }
        nfcCapsule.addView(nfcLabel)
        headerRow.addView(nfcCapsule)
        body.addView(headerRow, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = UiHelpers.dp(context, 18)
        })

        // 2. Clean Device & Connection Strip
        val deviceCard = createNeutralCard()
        val deviceRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val hp = UiHelpers.dp(context, 18)
            val vp = UiHelpers.dp(context, 16)
            setPadding(hp, vp, hp, vp)
            isClickable = true
            isFocusable = true
            UiHelpers.applyRowPressFeedback(this)
            setOnClickListener {
                HapticFeedbackEngine.playUiTap(context)
                callbacks.onOpenWifiPanel()
            }
        }
        val phoneBadge = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(UiHelpers.color(context, R.color.surface_subtle))
            }
        }
        val phoneIcon = ImageView(context).apply {
            setImageDrawable(UiHelpers.tintIcon(context, R.drawable.ic_phone_device, UiHelpers.color(context, R.color.text_primary)))
        }
        phoneBadge.addView(phoneIcon, FrameLayout.LayoutParams(UiHelpers.dp(context, 20), UiHelpers.dp(context, 20), Gravity.CENTER))
        deviceRow.addView(phoneBadge, LinearLayout.LayoutParams(UiHelpers.dp(context, 42), UiHelpers.dp(context, 42)).apply {
            marginEnd = UiHelpers.dp(context, 14)
        })

        val deviceMetaCol = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        deviceNameText = TextView(context).apply {
            text = settings.effectiveDeviceName()
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(UiHelpers.color(context, R.color.text_primary))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        deviceMetaCol.addView(deviceNameText)

        connectionText = TextView(context).apply {
            textSize = 12.5f
            setTextColor(UiHelpers.color(context, R.color.text_secondary))
            setPadding(0, UiHelpers.dp(context, 2), 0, 0)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        deviceMetaCol.addView(connectionText)
        deviceRow.addView(deviceMetaCol, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        val wifiChevron = ImageView(context).apply {
            setImageDrawable(UiHelpers.tintIcon(context, R.drawable.ic_wifi_signal, UiHelpers.color(context, R.color.text_tertiary)))
        }
        deviceRow.addView(wifiChevron, LinearLayout.LayoutParams(UiHelpers.dp(context, 18), UiHelpers.dp(context, 18)))
        deviceCard.addView(deviceRow)
        body.addView(deviceCard, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = UiHelpers.dp(context, 12)
        })

        // 3. Contextual Clipboard Quick-Send Row (only visible when clipboard has content)
        clipboardCard = createNeutralCard().apply {
            visibility = GONE
            UiHelpers.applyPressScale(this, 0.985f)
            setOnClickListener {
                HapticFeedbackEngine.playUiTap(context)
                callbacks.onSendClipboard()
            }
        }
        val clipRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val hp = UiHelpers.dp(context, 16)
            val vp = UiHelpers.dp(context, 13)
            setPadding(hp, vp, hp, vp)
        }
        val clipBadge = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = UiHelpers.dpF(context, 11f)
                setColor(UiHelpers.color(context, R.color.surface_subtle))
            }
        }
        clipboardIcon = ImageView(context)
        clipBadge.addView(clipboardIcon, FrameLayout.LayoutParams(UiHelpers.dp(context, 19), UiHelpers.dp(context, 19), Gravity.CENTER))
        clipRow.addView(clipBadge, LinearLayout.LayoutParams(UiHelpers.dp(context, 38), UiHelpers.dp(context, 38)).apply {
            marginEnd = UiHelpers.dp(context, 12)
        })

        val clipCol = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        clipboardTitle = TextView(context).apply {
            text = "クリップボード"
            textSize = 11.5f
            setTextColor(UiHelpers.color(context, R.color.text_secondary))
        }
        clipboardSubtitle = TextView(context).apply {
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(UiHelpers.color(context, R.color.text_primary))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, UiHelpers.dp(context, 1), 0, 0)
        }
        clipCol.addView(clipboardTitle)
        clipCol.addView(clipboardSubtitle)
        clipRow.addView(clipCol, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = UiHelpers.dp(context, 10)
        })

        val clipSendPill = TextView(context).apply {
            text = "送信"
            textSize = 12.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(UiHelpers.color(context, R.color.button_monochrome_text))
            val php = UiHelpers.dp(context, 14)
            val pvp = UiHelpers.dp(context, 7)
            setPadding(php, pvp, php, pvp)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = UiHelpers.dpF(context, 999f)
                setColor(UiHelpers.color(context, R.color.button_monochrome_fill))
            }
        }
        clipRow.addView(clipSendPill)
        clipboardCard.addView(clipRow)
        body.addView(clipboardCard, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = UiHelpers.dp(context, 12)
        })

        // 4. Clean 2x2 Action Grid (Icon + Short Title only)
        val gridRow1 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }
        gridRow1.addView(
            buildActionTile("写真・動画", R.drawable.ic_photo_library) { callbacks.onPickPhotosAndVideos() },
            LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = UiHelpers.dp(context, 10) }
        )
        gridRow1.addView(
            buildActionTile("ファイル", R.drawable.ic_file_doc) { callbacks.onPickFiles() },
            LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        )
        body.addView(gridRow1, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = UiHelpers.dp(context, 10)
        })

        val gridRow2 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }
        gridRow2.addView(
            buildActionTile("フォルダ", R.drawable.ic_folder) { callbacks.onPickFolder() },
            LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = UiHelpers.dp(context, 10) }
        )
        gridRow2.addView(
            buildActionTile("テキスト", R.drawable.ic_text_link) { callbacks.onComposeTextOrUrl() },
            LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        )
        body.addView(gridRow2, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = UiHelpers.dp(context, 22)
        })

        // 5. Recent Transfers Header + Quiet Inset Group Card
        val recentHeader = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(UiHelpers.dp(context, 4), 0, UiHelpers.dp(context, 4), UiHelpers.dp(context, 8))
        }
        val recentHeading = TextView(context).apply {
            text = "最近の履歴"
            textSize = 13.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(UiHelpers.color(context, R.color.text_secondary))
        }
        val seeAllBtn = TextView(context).apply {
            text = "すべて"
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(UiHelpers.color(context, R.color.text_secondary))
            setPadding(UiHelpers.dp(context, 8), UiHelpers.dp(context, 4), UiHelpers.dp(context, 2), UiHelpers.dp(context, 4))
            setOnClickListener {
                HapticFeedbackEngine.playUiTap(context)
                callbacks.onSeeAllHistory()
            }
        }
        recentHeader.addView(recentHeading, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        recentHeader.addView(seeAllBtn)
        body.addView(recentHeader)

        val recentCard = createNeutralCard()
        recentCardContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        recentListContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        recentCardContainer.addView(recentListContainer)

        recentEmptyText = TextView(context).apply {
            text = "履歴はありません"
            textSize = 13.5f
            setTextColor(UiHelpers.color(context, R.color.text_tertiary))
            gravity = Gravity.CENTER
            val p = UiHelpers.dp(context, 22)
            setPadding(p, p, p, p)
        }
        recentCardContainer.addView(recentEmptyText)
        recentCard.addView(recentCardContainer)
        body.addView(recentCard)

        addView(body, ViewGroup.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun createNeutralCard(): MaterialCardView = MaterialCardView(context).apply {
        radius = UiHelpers.dpF(context, 22f)
        cardElevation = 0f
        strokeWidth = 0
        setCardBackgroundColor(UiHelpers.color(context, R.color.surface_card))
    }

    private fun buildActionTile(title: String, iconRes: Int, onClick: () -> Unit): MaterialCardView {
        val card = createNeutralCard().apply {
            isClickable = true
            isFocusable = true
            UiHelpers.applyPressScale(this, 0.96f)
            setOnClickListener {
                HapticFeedbackEngine.playUiTap(context)
                onClick()
            }
        }
        val inner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            val hp = UiHelpers.dp(context, 18)
            val vp = UiHelpers.dp(context, 18)
            setPadding(hp, vp, hp, vp)
        }
        val iconCircle = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(UiHelpers.color(context, R.color.surface_subtle))
            }
        }
        val icon = ImageView(context).apply {
            setImageDrawable(UiHelpers.tintIcon(context, iconRes, UiHelpers.color(context, R.color.text_primary)))
        }
        iconCircle.addView(icon, FrameLayout.LayoutParams(UiHelpers.dp(context, 20), UiHelpers.dp(context, 20), Gravity.CENTER))
        inner.addView(iconCircle, LinearLayout.LayoutParams(UiHelpers.dp(context, 42), UiHelpers.dp(context, 42)).apply {
            bottomMargin = UiHelpers.dp(context, 14)
        })

        val label = TextView(context).apply {
            text = title
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(UiHelpers.color(context, R.color.text_primary))
            maxLines = 1
        }
        inner.addView(label)
        card.addView(inner)
        return card
    }

    fun updateStatus(nfcEnabled: Boolean, wifiEnabled: Boolean, wifi: WifiSnapshot) {
        deviceNameText.text = settings.effectiveDeviceName()

        val pillFill = UiHelpers.color(context, R.color.surface_card)
        val dotColor = if (nfcEnabled) {
            UiHelpers.color(context, R.color.accent_emerald)
        } else {
            UiHelpers.color(context, R.color.accent_coral)
        }
        nfcDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(dotColor)
        }
        nfcCapsule.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = UiHelpers.dpF(context, 999f)
            setColor(pillFill)
        }
        nfcLabel.text = if (nfcEnabled) "NFC 待機中" else "NFC オフ"
        nfcLabel.setTextColor(UiHelpers.color(context, R.color.text_primary))

        connectionText.text = when {
            !wifiEnabled -> "Wi-Fi オフ"
            wifi.hasWifiTransport -> "Wi-Fi 接続中"
            else -> "P2P 待機可能"
        }
    }

    fun updateClipboardPreview(preview: ClipboardPreview) {
        when (preview) {
            is ClipboardPreview.Empty -> {
                clipboardCard.visibility = GONE
            }
            is ClipboardPreview.Url -> {
                clipboardCard.visibility = VISIBLE
                clipboardIcon.setImageDrawable(UiHelpers.tintIcon(context, R.drawable.ic_text_link, UiHelpers.color(context, R.color.text_primary)))
                clipboardSubtitle.text = preview.url
            }
            is ClipboardPreview.Text -> {
                clipboardCard.visibility = VISIBLE
                clipboardIcon.setImageDrawable(UiHelpers.tintIcon(context, R.drawable.ic_clipboard, UiHelpers.color(context, R.color.text_primary)))
                clipboardSubtitle.text = preview.text.lineSequence().firstOrNull().orEmpty().ifBlank { preview.text }
            }
            is ClipboardPreview.ContentUri -> {
                clipboardCard.visibility = VISIBLE
                clipboardIcon.setImageDrawable(UiHelpers.tintIcon(context, R.drawable.ic_file_doc, UiHelpers.color(context, R.color.text_primary)))
                clipboardSubtitle.text = preview.displayLabel
            }
        }
    }

    fun updateRecentHistory(items: List<HistoryEntry>) {
        recentListContainer.removeAllViews()
        val recent = items.take(4)
        if (recent.isEmpty()) {
            recentListContainer.visibility = GONE
            recentEmptyText.visibility = VISIBLE
            return
        }
        recentListContainer.visibility = VISIBLE
        recentEmptyText.visibility = GONE

        recent.forEachIndexed { idx, entry ->
            if (idx > 0) {
                recentListContainer.addView(View(context).apply {
                    setBackgroundColor(UiHelpers.color(context, R.color.divider_inset))
                }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 1).apply {
                    marginStart = UiHelpers.dp(context, 66)
                    marginEnd = UiHelpers.dp(context, 16)
                })
            }
            recentListContainer.addView(buildRecentRow(entry))
        }
    }

    private fun buildRecentRow(entry: HistoryEntry): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val hp = UiHelpers.dp(context, 16)
            val vp = UiHelpers.dp(context, 12)
            setPadding(hp, vp, hp, vp)
            isClickable = true
            isFocusable = true
            UiHelpers.applyRowPressFeedback(this)
            setOnClickListener {
                HapticFeedbackEngine.playUiTap(context)
                callbacks.onHistoryClick(entry)
            }
        }

        val thumbContainer = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = UiHelpers.dpF(context, 11f)
                setColor(UiHelpers.color(context, R.color.surface_subtle))
            }
            clipToOutline = true
        }
        val iconRes = when (entry.category) {
            "IMAGE" -> R.drawable.ic_photo_library
            "VIDEO" -> R.drawable.ic_video
            "URL" -> R.drawable.ic_text_link
            "TEXT" -> R.drawable.ic_clipboard
            else -> R.drawable.ic_file_doc
        }
        val iconView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageDrawable(UiHelpers.tintIcon(context, iconRes, UiHelpers.color(context, R.color.text_primary)))
            val ip = UiHelpers.dp(context, 9)
            setPadding(ip, ip, ip, ip)
        }
        thumbContainer.addView(iconView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        if ((entry.category == "IMAGE" || entry.category == "VIDEO") && !entry.uriString.isNullOrBlank()) {
            uiScope.launch {
                val bmp = ThumbnailLoader.loadThumbnail(context, Uri.parse(entry.uriString), entry.mimeType ?: "*/*", 128)
                if (bmp != null) {
                    iconView.setPadding(0, 0, 0, 0)
                    iconView.setImageBitmap(bmp)
                }
            }
        }
        row.addView(thumbContainer, LinearLayout.LayoutParams(UiHelpers.dp(context, 38), UiHelpers.dp(context, 38)).apply {
            marginEnd = UiHelpers.dp(context, 12)
        })

        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val title = TextView(context).apply {
            text = entry.title
            textSize = 14.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(UiHelpers.color(context, R.color.text_primary))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val dirLabel = if (entry.direction == "SEND") "送信" else "受信"
        val sizeStr = if (entry.totalBytes > 0L) " · ${UiHelpers.formatBytes(entry.totalBytes)}" else ""
        val meta = TextView(context).apply {
            text = "$dirLabel$sizeStr · ${UiHelpers.formatRelativeTime(entry.timestampMs)}"
            textSize = 12f
            setTextColor(UiHelpers.color(context, R.color.text_secondary))
            fontFeatureSettings = "tnum"
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, UiHelpers.dp(context, 2), 0, 0)
        }
        col.addView(title)
        col.addView(meta)
        row.addView(col, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        return row
    }
}
