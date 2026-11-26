package fun7772ei.taptoshare.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.DrawableRes
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import fun7772ei.taptoshare.AppSettings
import fun7772ei.taptoshare.InstalledAppOption
import fun7772ei.taptoshare.R
import fun7772ei.taptoshare.ReceiveActionHandler
import fun7772ei.taptoshare.TapToShareApp
import fun7772ei.taptoshare.ThemeMode
import fun7772ei.taptoshare.UiHelpers

interface SettingsTabCallbacks {
    fun onPlaySendDemo()
    fun onPlayReceiveDemo()
    fun onChooseDestinationFolder()
    fun onResetDestinationFolder()
    fun onRunBenchmark()
    fun onDynamicColorChanged()
    fun onDeviceNicknameChanged()
}

/**
 * Clean neutral grouped settings view:
 * - High-contrast neutral surface cards (22dp corners) on neutral canvas
 * - Inset hairline dividers aligned cleanly to label start
 */
class SettingsTabView(
    context: Context,
    private val settings: AppSettings,
    private val callbacks: SettingsTabCallbacks
) : ScrollView(context) {

    private lateinit var destValueText: TextView
    private lateinit var resetDestRow: View
    private lateinit var nicknameValueText: TextView
    private lateinit var themeSegmented: SegmentedPillView
    private lateinit var soundEffectsSwitch: MaterialSwitch
    private var dynamicColorSwitch: MaterialSwitch? = null
    private lateinit var autoCloseSwitch: MaterialSwitch
    private var suppressCallbacks = false

    init {
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        clipToPadding = false
        setBackgroundColor(Color.TRANSPARENT)

        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val hp = UiHelpers.dp(context, 18)
            setPadding(hp, UiHelpers.dp(context, 18), hp, UiHelpers.dp(context, 114))
        }

        val pageTitle = TextView(context).apply {
            text = "設定"
            textSize = 27f
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = -0.025f
            setTextColor(UiHelpers.color(context, R.color.text_primary))
        }
        body.addView(pageTitle, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = UiHelpers.dp(context, 14)
        })

        // ── Section 1: 外観とエフェクト ──
        body.addView(buildSectionHeader("外観とエフェクト"))
        body.addView(buildGroupCard { group ->
            val themeBox = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val hp = UiHelpers.dp(context, 16)
                val vp = UiHelpers.dp(context, 14)
                setPadding(hp, vp, hp, vp)
            }
            val themeLabel = TextView(context).apply {
                text = "外観モード"
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(UiHelpers.color(context, R.color.text_primary))
                setPadding(0, 0, 0, UiHelpers.dp(context, 10))
            }
            themeSegmented = SegmentedPillView(context).apply {
                val modes = listOf(ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK)
                setOptions(
                    listOf("システム", "ライト", "ダーク"),
                    modes.indexOf(settings.themeMode).coerceAtLeast(0)
                ) { idx ->
                    val chosen = modes[idx]
                    if (chosen != settings.themeMode) {
                        settings.themeMode = chosen
                        TapToShareApp.applyThemeMode(chosen)
                    }
                }
            }
            themeBox.addView(themeLabel)
            themeBox.addView(themeSegmented, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            group.addView(themeBox)

            group.addView(buildInsetDivider())
            val soundRow = buildSwitchRowWithRef(
                iconRes = R.drawable.ic_volume,
                title = "効果音",
                checked = settings.soundEffectsEnabled
            ) { settings.soundEffectsEnabled = it }
            soundEffectsSwitch = soundRow.second
            group.addView(soundRow.first)

            group.addView(buildInsetDivider())
            group.addView(buildSwitchRow(
                iconRes = R.drawable.ic_vibration,
                title = "完了バイブレーション",
                checked = settings.vibrateOnComplete
            ) { settings.vibrateOnComplete = it })

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                group.addView(buildInsetDivider())
                val dynamicRow = buildSwitchRowWithRef(
                    iconRes = R.drawable.ic_sparkles,
                    title = "ダイナミックカラー",
                    checked = settings.dynamicColorEnabled
                ) {
                    if (settings.dynamicColorEnabled != it) {
                        settings.dynamicColorEnabled = it
                        callbacks.onDynamicColorChanged()
                    }
                }
                dynamicColorSwitch = dynamicRow.second
                group.addView(dynamicRow.first)
            }

            group.addView(buildInsetDivider())
            group.addView(buildActionRow(
                iconRes = R.drawable.ic_send_up,
                title = "送信エフェクトを再生"
            ) { callbacks.onPlaySendDemo() })

            group.addView(buildInsetDivider())
            group.addView(buildActionRow(
                iconRes = R.drawable.ic_receive_down,
                title = "受信エフェクトを再生"
            ) { callbacks.onPlayReceiveDemo() })
        })

        // ── Section 2: 送信設定 ──
        body.addView(buildSectionHeader("送信設定"))
        body.addView(buildGroupCard { group ->
            val nameRow = buildValuePickerRow(
                iconRes = R.drawable.ic_phone_device,
                title = "デバイス名",
                initialValue = settings.effectiveDeviceName()
            ) { showNicknameDialog() }
            nicknameValueText = nameRow.second
            group.addView(nameRow.first)

            group.addView(buildInsetDivider())
            group.addView(buildSwitchRow(
                iconRes = R.drawable.ic_wifi_signal,
                title = "同一 Wi-Fi を優先",
                checked = settings.preferLanFirst
            ) { settings.preferLanFirst = it })

            group.addView(buildInsetDivider())
            val autoCloseRow = buildSwitchRowWithRef(
                iconRes = R.drawable.ic_send_up,
                title = "送信後に自動で閉じる",
                checked = settings.autoCloseAfterSend
            ) { settings.autoCloseAfterSend = it }
            autoCloseSwitch = autoCloseRow.second
            group.addView(autoCloseRow.first)
        })

        // ── Section 3: 受信設定 ──
        body.addView(buildSectionHeader("受信設定"))
        body.addView(buildGroupCard { group ->
            val destPair = buildValuePickerRow(
                iconRes = R.drawable.ic_folder,
                title = "保存先",
                initialValue = settings.getDestinationDisplayName()
            ) { callbacks.onChooseDestinationFolder() }
            destValueText = destPair.second
            group.addView(destPair.first)

            val resetDivider = buildInsetDivider()
            val resetRow = buildActionRow(
                iconRes = R.drawable.ic_delete,
                title = "既定の保存先に戻す"
            ) { callbacks.onResetDestinationFolder() }
            resetDestRow = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                visibility = if (settings.getCustomTreeUri() != null) VISIBLE else GONE
                addView(resetDivider)
                addView(resetRow)
            }
            group.addView(resetDestRow)

            group.addView(buildInsetDivider())
            group.addView(buildSwitchRow(
                iconRes = R.drawable.ic_text_link,
                title = "リンクを自動で開く",
                checked = settings.urlAutoOpen
            ) { settings.urlAutoOpen = it })

            group.addView(buildInsetDivider())
            group.addView(buildSwitchRow(
                iconRes = R.drawable.ic_copy,
                title = "リンクを自動コピー",
                checked = settings.urlAutoCopy
            ) { settings.urlAutoCopy = it })

            group.addView(buildInsetDivider())
            group.addView(buildAppSelectorRow(
                iconRes = R.drawable.ic_open_external,
                title = "ブラウザ",
                getOptions = { ReceiveActionHandler.queryInstalledBrowsers(context) },
                getCurrentPkg = { settings.browserPackage },
                onSelectedPkg = { settings.browserPackage = it }
            ))

            group.addView(buildInsetDivider())
            group.addView(buildSwitchRow(
                iconRes = R.drawable.ic_clipboard,
                title = "テキストを自動コピー",
                checked = settings.textAutoCopy
            ) { settings.textAutoCopy = it })

            group.addView(buildInsetDivider())
            group.addView(buildSwitchRow(
                iconRes = R.drawable.ic_photo_library,
                title = "画像を自動で開く",
                checked = settings.imageAutoOpen
            ) { settings.imageAutoOpen = it })

            group.addView(buildInsetDivider())
            group.addView(buildSwitchRow(
                iconRes = R.drawable.ic_copy,
                title = "画像を自動コピー",
                checked = settings.imageAutoCopyUri
            ) { settings.imageAutoCopyUri = it })

            group.addView(buildInsetDivider())
            group.addView(buildAppSelectorRow(
                iconRes = R.drawable.ic_photo_library,
                title = "画像ビューア",
                getOptions = { ReceiveActionHandler.queryInstalledImageViewers(context) },
                getCurrentPkg = { settings.imageViewerPackage },
                onSelectedPkg = { settings.imageViewerPackage = it }
            ))

            group.addView(buildInsetDivider())
            group.addView(buildSwitchRow(
                iconRes = R.drawable.ic_video,
                title = "動画を自動で開く",
                checked = settings.videoAutoOpen
            ) { settings.videoAutoOpen = it })

            group.addView(buildInsetDivider())
            group.addView(buildAppSelectorRow(
                iconRes = R.drawable.ic_video,
                title = "動画プレーヤー",
                getOptions = { ReceiveActionHandler.queryInstalledVideoPlayers(context) },
                getCurrentPkg = { settings.videoPlayerPackage },
                onSelectedPkg = { settings.videoPlayerPackage = it }
            ))

            group.addView(buildInsetDivider())
            group.addView(buildSwitchRow(
                iconRes = R.drawable.ic_file_doc,
                title = "ファイルを自動で開く",
                checked = settings.otherAutoOpen
            ) { settings.otherAutoOpen = it })
        })

        // ── Section 4: 診断 ──
        body.addView(buildSectionHeader("診断"))
        body.addView(buildGroupCard { group ->
            group.addView(buildActionRow(
                iconRes = R.drawable.ic_wifi_signal,
                title = "20MB P2P 速度計測"
            ) { callbacks.onRunBenchmark() })
        })

        addView(body, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun buildSectionHeader(title: String): TextView = TextView(context).apply {
        text = title
        textSize = 13f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(UiHelpers.color(context, R.color.text_secondary))
        setPadding(UiHelpers.dp(context, 8), UiHelpers.dp(context, 16), 0, UiHelpers.dp(context, 8))
    }

    private fun buildGroupCard(populate: (LinearLayout) -> Unit): MaterialCardView {
        val card = MaterialCardView(context).apply {
            radius = UiHelpers.dpF(context, 22f)
            cardElevation = 0f
            strokeWidth = 0
            setCardBackgroundColor(UiHelpers.color(context, R.color.surface_card))
        }
        val stack = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        populate(stack)
        card.addView(stack)
        return card
    }

    private fun buildInsetDivider(): View = View(context).apply {
        setBackgroundColor(UiHelpers.color(context, R.color.divider_inset))
        layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 1).apply {
            marginStart = UiHelpers.dp(context, 60)
            marginEnd = UiHelpers.dp(context, 16)
        }
    }

    private fun buildLeadingIcon(@DrawableRes iconRes: Int): FrameLayout {
        val badge = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = UiHelpers.dpF(context, 9f)
                setColor(UiHelpers.color(context, R.color.surface_subtle))
            }
        }
        val icon = ImageView(context).apply {
            setImageDrawable(UiHelpers.tintIcon(context, iconRes, UiHelpers.color(context, R.color.text_primary)))
        }
        badge.addView(icon, FrameLayout.LayoutParams(UiHelpers.dp(context, 18), UiHelpers.dp(context, 18), Gravity.CENTER))
        badge.layoutParams = LinearLayout.LayoutParams(UiHelpers.dp(context, 32), UiHelpers.dp(context, 32)).apply {
            marginEnd = UiHelpers.dp(context, 12)
        }
        return badge
    }

    private fun createOemSwitch(checked: Boolean, onChanged: (Boolean) -> Unit): MaterialSwitch {
        val fallbackAccent = UiHelpers.color(context, R.color.accent_primary)
        val accent = if (settings.dynamicColorEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MaterialColors.getColor(
                context,
                androidx.appcompat.R.attr.colorPrimary,
                fallbackAccent
            )
        } else {
            fallbackAccent
        }
        val trackOff = UiHelpers.color(context, R.color.surface_subtle)
        val thumbOff = UiHelpers.color(context, R.color.text_tertiary)
        return MaterialSwitch(context).apply {
            isChecked = checked
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(accent, trackOff)
            )
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(Color.WHITE, thumbOff)
            )
            trackDecorationTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            setOnCheckedChangeListener { _, isChecked ->
                if (suppressCallbacks) return@setOnCheckedChangeListener
                HapticFeedbackEngine.playUiTap(context)
                onChanged(isChecked)
            }
        }
    }

    private fun buildSwitchRow(
        @DrawableRes iconRes: Int,
        title: String,
        checked: Boolean,
        onChanged: (Boolean) -> Unit
    ): LinearLayout = buildSwitchRowWithRef(iconRes, title, checked, onChanged).first

    private fun buildSwitchRowWithRef(
        @DrawableRes iconRes: Int,
        title: String,
        checked: Boolean,
        onChanged: (Boolean) -> Unit
    ): Pair<LinearLayout, MaterialSwitch> {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val hp = UiHelpers.dp(context, 16)
            val vp = UiHelpers.dp(context, 11)
            setPadding(hp, vp, hp, vp)
            isClickable = true
            isFocusable = true
        }
        row.addView(buildLeadingIcon(iconRes))

        val titleTv = TextView(context).apply {
            text = title
            textSize = 15f
            setTextColor(UiHelpers.color(context, R.color.text_primary))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        row.addView(titleTv, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = UiHelpers.dp(context, 12)
        })

        val switchView = createOemSwitch(checked, onChanged)
        row.setOnClickListener { switchView.toggle() }
        row.addView(switchView)
        return row to switchView
    }

    private fun buildActionRow(
        @DrawableRes iconRes: Int,
        title: String,
        onClick: () -> Unit
    ): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val hp = UiHelpers.dp(context, 16)
            val vp = UiHelpers.dp(context, 14)
            setPadding(hp, vp, hp, vp)
            isClickable = true
            isFocusable = true
            UiHelpers.applyRowPressFeedback(this)
            setOnClickListener {
                HapticFeedbackEngine.playUiTap(context)
                onClick()
            }
        }
        row.addView(buildLeadingIcon(iconRes))

        val titleTv = TextView(context).apply {
            text = title
            textSize = 15f
            setTextColor(UiHelpers.color(context, R.color.text_primary))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        row.addView(titleTv, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun buildValuePickerRow(
        @DrawableRes iconRes: Int,
        title: String,
        initialValue: String,
        onClick: () -> Unit
    ): Pair<LinearLayout, TextView> {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val hp = UiHelpers.dp(context, 16)
            val vp = UiHelpers.dp(context, 14)
            setPadding(hp, vp, hp, vp)
            isClickable = true
            isFocusable = true
            UiHelpers.applyRowPressFeedback(this)
            setOnClickListener {
                HapticFeedbackEngine.playUiTap(context)
                onClick()
            }
        }
        row.addView(buildLeadingIcon(iconRes))

        val titleTv = TextView(context).apply {
            text = title
            textSize = 15f
            setTextColor(UiHelpers.color(context, R.color.text_primary))
        }
        row.addView(titleTv, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            marginEnd = UiHelpers.dp(context, 12)
        })

        val valueTv = TextView(context).apply {
            text = initialValue
            textSize = 14f
            setTextColor(UiHelpers.color(context, R.color.text_secondary))
            gravity = Gravity.END
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        row.addView(valueTv, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        return row to valueTv
    }

    private fun buildAppSelectorRow(
        @DrawableRes iconRes: Int,
        title: String,
        getOptions: () -> List<InstalledAppOption>,
        getCurrentPkg: () -> String?,
        onSelectedPkg: (String?) -> Unit
    ): LinearLayout {
        fun resolveAppName(pkg: String?): String {
            if (pkg.isNullOrBlank()) return "標準"
            return getOptions().firstOrNull { it.packageName == pkg }?.label ?: "標準"
        }

        var valueTvRef: TextView? = null
        val (row, valueTv) = buildValuePickerRow(iconRes, title, resolveAppName(getCurrentPkg())) {
            val options = getOptions()
            val labels = arrayOf("標準") + options.map { it.label }.toTypedArray()
            val curPkg = getCurrentPkg()
            val selectedIndex = if (curPkg == null) 0 else {
                val idx = options.indexOfFirst { it.packageName == curPkg }
                if (idx >= 0) idx + 1 else 0
            }
            MaterialAlertDialogBuilder(context)
                .setTitle(title)
                .setSingleChoiceItems(labels, selectedIndex) { dialog, which ->
                    val chosen = if (which == 0) null else options[which - 1].packageName
                    onSelectedPkg(chosen)
                    valueTvRef?.text = resolveAppName(chosen)
                    dialog.dismiss()
                }
                .setNegativeButton("キャンセル", null)
                .show()
        }
        valueTvRef = valueTv
        return row
    }

    fun refreshDestinationLabel() {
        destValueText.text = settings.getDestinationDisplayName()
        resetDestRow.visibility = if (settings.getCustomTreeUri() != null) VISIBLE else GONE
    }

    fun syncFromSettings() {
        suppressCallbacks = true
        try {
            val modes = listOf(ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK)
            themeSegmented.select(modes.indexOf(settings.themeMode).coerceAtLeast(0), animate = false)
            soundEffectsSwitch.isChecked = settings.soundEffectsEnabled
            dynamicColorSwitch?.isChecked = settings.dynamicColorEnabled
            autoCloseSwitch.isChecked = settings.autoCloseAfterSend
            nicknameValueText.text = settings.effectiveDeviceName()
            refreshDestinationLabel()
        } finally {
            suppressCallbacks = false
        }
    }

    private fun showNicknameDialog() {
        val til = TextInputLayout(context).apply {
            hint = "デバイス名"
            val p = UiHelpers.dp(context, 18)
            setPadding(p, UiHelpers.dp(context, 10), p, 0)
        }
        val edit = TextInputEditText(til.context).apply {
            setText(settings.deviceNickname)
        }
        til.addView(edit)

        MaterialAlertDialogBuilder(context)
            .setTitle("デバイス名")
            .setView(til)
            .setPositiveButton("保存") { _, _ ->
                settings.deviceNickname = edit.text?.toString().orEmpty()
                nicknameValueText.text = settings.effectiveDeviceName()
                callbacks.onDeviceNicknameChanged()
            }
            .setNeutralButton("標準に戻す") { _, _ ->
                settings.deviceNickname = ""
                nicknameValueText.text = settings.effectiveDeviceName()
                callbacks.onDeviceNicknameChanged()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }
}
