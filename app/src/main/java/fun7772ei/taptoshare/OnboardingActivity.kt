package fun7772ei.taptoshare

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import fun7772ei.taptoshare.ui.HapticFeedbackEngine
import fun7772ei.taptoshare.ui.SegmentedPillView

class OnboardingActivity : AppCompatActivity() {

    companion object {
        private const val TOTAL_STEPS = 4
        private const val STATE_CURRENT_STEP = "onboarding_current_step"
        private const val STATE_NICKNAME_DRAFT = "onboarding_nickname_draft"
        private const val STATE_PERM_PERMANENTLY_DENIED = "onboarding_perm_permanently_denied"
    }

    private lateinit var settings: AppSettings
    private var currentStep = 0
    private var nicknameDraft = ""
    private var permissionPermanentlyDenied = false

    private lateinit var stepCounterText: TextView
    private lateinit var stepCaptionText: TextView
    private val progressSegments = mutableListOf<View>()

    private lateinit var contentScroll: ScrollView
    private val stepViews = mutableListOf<View>()

    private var nicknameEdit: TextInputEditText? = null
    private lateinit var backButton: MaterialButton
    private lateinit var nextButton: MaterialButton

    private var wifiPermBadge: TextView? = null
    private var notifPermBadge: TextView? = null
    private var permissionButton: MaterialButton? = null
    private var nfcWarningCard: MaterialCardView? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val remaining = UiHelpers.missingAllRecommendedPermissions(this)
        permissionPermanentlyDenied = remaining.isNotEmpty() && remaining.none {
            ActivityCompat.shouldShowRequestPermissionRationale(this, it)
        }
        refreshStep3Status()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = AppSettings(this)
        currentStep = savedInstanceState?.getInt(STATE_CURRENT_STEP, 0)?.coerceIn(0, TOTAL_STEPS - 1) ?: 0
        nicknameDraft = if (savedInstanceState != null && savedInstanceState.containsKey(STATE_NICKNAME_DRAFT)) {
            savedInstanceState.getString(STATE_NICKNAME_DRAFT).orEmpty()
        } else {
            settings.deviceNickname ?: settings.defaultModelName()
        }
        permissionPermanentlyDenied = savedInstanceState?.getBoolean(STATE_PERM_PERMANENTLY_DENIED, false) ?: false

        UiHelpers.configureEdgeToEdgeBars(this)
        buildUi()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentStep > 0) {
                    if (currentStep == 1) {
                        persistStep2Settings()
                    }
                    showStep(currentStep - 1, animate = true, forward = false)
                } else {
                    setResult(RESULT_CANCELED)
                    finish()
                }
            }
        })

        showStep(currentStep, animate = false, forward = true)
        refreshStep3Status()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        nicknameEdit?.text?.toString()?.let { nicknameDraft = it }
        outState.putInt(STATE_CURRENT_STEP, currentStep)
        outState.putString(STATE_NICKNAME_DRAFT, nicknameDraft)
        outState.putBoolean(STATE_PERM_PERMANENTLY_DENIED, permissionPermanentlyDenied)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        refreshStep3Status()
    }

    override fun onPause() {
        persistStep2Settings()
        super.onPause()
    }

    private fun resolvedAccentColor(): Int {
        return MaterialColors.getColor(
            this,
            androidx.appcompat.R.attr.colorPrimary,
            UiHelpers.color(this, R.color.accent_primary)
        )
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(UiHelpers.color(this@OnboardingActivity, R.color.bg_canvas))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // ── Top Step Header ──
        val headerContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val hp = UiHelpers.dp(this@OnboardingActivity, 20)
            setPadding(hp, UiHelpers.dp(this@OnboardingActivity, 14), hp, UiHelpers.dp(this@OnboardingActivity, 10))
        }

        val headerMetaRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        stepCounterText = TextView(this).apply {
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            fontFeatureSettings = "tnum"
            setTextColor(UiHelpers.color(this@OnboardingActivity, R.color.text_secondary))
        }
        stepCaptionText = TextView(this).apply {
            textSize = 12.5f
            setTextColor(UiHelpers.color(this@OnboardingActivity, R.color.text_tertiary))
        }
        headerMetaRow.addView(stepCounterText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        headerMetaRow.addView(stepCaptionText)
        headerContainer.addView(headerMetaRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = UiHelpers.dp(this@OnboardingActivity, 10)
        })

        val progressRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        progressSegments.clear()
        for (i in 0 until TOTAL_STEPS) {
            val seg = View(this)
            progressSegments.add(seg)
            progressRow.addView(seg, LinearLayout.LayoutParams(0, UiHelpers.dp(this, 4), 1f).apply {
                if (i < TOTAL_STEPS - 1) {
                    marginEnd = UiHelpers.dp(this@OnboardingActivity, 6)
                }
            })
        }
        headerContainer.addView(progressRow)
        root.addView(headerContainer)

        // ── Middle Scrollable Step Host ──
        contentScroll = ScrollView(this).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            setBackgroundColor(Color.TRANSPARENT)
        }
        val stepHost = FrameLayout(this).apply {
            val hp = UiHelpers.dp(this@OnboardingActivity, 18)
            setPadding(hp, UiHelpers.dp(this@OnboardingActivity, 10), hp, UiHelpers.dp(this@OnboardingActivity, 20))
        }

        stepViews.clear()
        stepViews.add(buildStep1View())
        stepViews.add(buildStep2View())
        stepViews.add(buildStep3View())
        stepViews.add(buildStep4View())
        stepViews.forEach { v ->
            v.visibility = View.GONE
            stepHost.addView(v, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        contentScroll.addView(stepHost, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        root.addView(contentScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // ── Bottom Navigation Bar ──
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val hp = UiHelpers.dp(this@OnboardingActivity, 18)
            setPadding(hp, UiHelpers.dp(this@OnboardingActivity, 12), hp, UiHelpers.dp(this@OnboardingActivity, 16))
            setBackgroundColor(UiHelpers.color(this@OnboardingActivity, R.color.bg_canvas))
        }

        backButton = MaterialButton(this).apply {
            text = "戻る"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            isAllCaps = false
            cornerRadius = UiHelpers.dp(this@OnboardingActivity, 999)
            setBackgroundColor(UiHelpers.color(this@OnboardingActivity, R.color.surface_card))
            setTextColor(UiHelpers.color(this@OnboardingActivity, R.color.text_primary))
            elevation = 0f
            stateListAnimator = null
            UiHelpers.applyPressScale(this, 0.97f)
            setOnClickListener {
                HapticFeedbackEngine.playUiTap(this@OnboardingActivity)
                if (currentStep == 1) {
                    persistStep2Settings()
                }
                if (currentStep > 0) {
                    showStep(currentStep - 1, animate = true, forward = false)
                }
            }
        }
        nextButton = MaterialButton(this).apply {
            text = "次へ"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            isAllCaps = false
            cornerRadius = UiHelpers.dp(this@OnboardingActivity, 999)
            setBackgroundColor(UiHelpers.color(this@OnboardingActivity, R.color.button_monochrome_fill))
            setTextColor(UiHelpers.color(this@OnboardingActivity, R.color.button_monochrome_text))
            elevation = 0f
            stateListAnimator = null
            UiHelpers.applyPressScale(this, 0.97f)
            setOnClickListener {
                HapticFeedbackEngine.playUiTap(this@OnboardingActivity)
                onNextClicked()
            }
        }

        val btnHeight = UiHelpers.dp(this, 52)
        bottomBar.addView(backButton, LinearLayout.LayoutParams(0, btnHeight, 1f).apply {
            marginEnd = UiHelpers.dp(this@OnboardingActivity, 10)
        })
        bottomBar.addView(nextButton, LinearLayout.LayoutParams(0, btnHeight, 1.6f))
        root.addView(bottomBar)

        val baseHeaderLeft = UiHelpers.dp(this, 20)
        val baseHeaderTop = UiHelpers.dp(this, 14)
        val baseHeaderRight = UiHelpers.dp(this, 20)
        val baseHeaderBottom = UiHelpers.dp(this, 10)

        val baseBottomLeft = UiHelpers.dp(this, 18)
        val baseBottomTop = UiHelpers.dp(this, 12)
        val baseBottomRight = UiHelpers.dp(this, 18)
        val baseBottomBottom = UiHelpers.dp(this, 16)

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            headerContainer.setPadding(
                baseHeaderLeft + sys.left,
                baseHeaderTop + sys.top,
                baseHeaderRight + sys.right,
                baseHeaderBottom
            )
            bottomBar.setPadding(
                baseBottomLeft + sys.left,
                baseBottomTop,
                baseBottomRight + sys.right,
                baseBottomBottom + maxOf(sys.bottom, ime.bottom)
            )
            insets
        }

        setContentView(root)
    }

    // ── Step 1: ようこそ ＆ 外観設定 ──
    private fun buildStep1View(): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Hero Welcome Section
        val heroBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(
                UiHelpers.dp(this@OnboardingActivity, 8),
                UiHelpers.dp(this@OnboardingActivity, 16),
                UiHelpers.dp(this@OnboardingActivity, 8),
                UiHelpers.dp(this@OnboardingActivity, 20)
            )
        }

        val iconCard = MaterialCardView(this).apply {
            radius = UiHelpers.dpF(this@OnboardingActivity, 22f)
            cardElevation = 0f
            strokeWidth = 0
            preventCornerOverlap = true
            setCardBackgroundColor(UiHelpers.color(this@OnboardingActivity, R.color.surface_card))
        }
        val iconView = ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val iconInset = UiHelpers.dp(this, 6)
        iconCard.addView(iconView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            Gravity.CENTER
        ).apply {
            setMargins(iconInset, iconInset, iconInset, iconInset)
        })
        heroBlock.addView(iconCard, LinearLayout.LayoutParams(
            UiHelpers.dp(this, 72),
            UiHelpers.dp(this, 72)
        ).apply {
            bottomMargin = UiHelpers.dp(this@OnboardingActivity, 18)
        })

        val welcomeTitle = TextView(this).apply {
            text = "Tap to Share へようこそ"
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = -0.025f
            gravity = Gravity.CENTER
            setTextColor(UiHelpers.color(this@OnboardingActivity, R.color.text_primary))
        }
        heroBlock.addView(welcomeTitle, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = UiHelpers.dp(this@OnboardingActivity, 8)
        })

        val welcomeSubtitle = TextView(this).apply {
            text = "かざすだけで、リンクや写真・ファイルを\nすぐに共有できます"
            textSize = 14.5f
            gravity = Gravity.CENTER
            setLineSpacing(UiHelpers.dpF(this@OnboardingActivity, 3f), 1f)
            setTextColor(UiHelpers.color(this@OnboardingActivity, R.color.text_secondary))
        }
        heroBlock.addView(welcomeSubtitle, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = UiHelpers.dp(this@OnboardingActivity, 14)
        })

        val pillRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        pillRow.addView(buildFeatureChip("NFC タッチ共有"), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            marginEnd = UiHelpers.dp(this@OnboardingActivity, 8)
        })
        pillRow.addView(buildFeatureChip("高速 Wi-Fi 転送"))
        heroBlock.addView(pillRow)

        container.addView(heroBlock)

        // Appearance Card
        container.addView(buildSectionHeader("外観"))
        container.addView(buildGroupCard { group ->
            val themeBox = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val hp = UiHelpers.dp(this@OnboardingActivity, 16)
                val vp = UiHelpers.dp(this@OnboardingActivity, 16)
                setPadding(hp, vp, hp, vp)
            }
            val themeLabel = TextView(this).apply {
                text = "外観モード"
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(UiHelpers.color(this@OnboardingActivity, R.color.text_primary))
                setPadding(0, 0, 0, UiHelpers.dp(this@OnboardingActivity, 10))
            }
            val themeSegmented = SegmentedPillView(this).apply {
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
            themeBox.addView(themeSegmented, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            group.addView(themeBox)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                group.addView(buildInsetDivider())
                group.addView(buildSwitchRow(
                    iconRes = R.drawable.ic_sparkles,
                    title = "ダイナミックカラー",
                    subtitle = "壁紙の色合いに合わせてアクセントを調整",
                    checked = settings.dynamicColorEnabled
                ) { isChecked ->
                    if (settings.dynamicColorEnabled != isChecked) {
                        settings.dynamicColorEnabled = isChecked
                        recreate()
                    }
                })
            }
        })

        return container
    }

    // ── Step 2: デバイスと送信時の設定 ──
    private fun buildStep2View(): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        container.addView(buildStepTitle("デバイスと送信時の設定"))
        container.addView(buildStepSubtitle("相手端末に表示される名前や送信時の動作を設定します。\n設定タブからいつでも変更できます。"))

        container.addView(buildSectionHeader("この端末の名前"))
        container.addView(buildGroupCard { group ->
            val nameCardBody = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val hp = UiHelpers.dp(this@OnboardingActivity, 16)
                val vp = UiHelpers.dp(this@OnboardingActivity, 16)
                setPadding(hp, vp, hp, vp)
            }

            val labelRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            labelRow.addView(buildLeadingIcon(R.drawable.ic_phone_device))
            labelRow.addView(TextView(this).apply {
                text = "デバイス名"
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(UiHelpers.color(this@OnboardingActivity, R.color.text_primary))
            })
            nameCardBody.addView(labelRow, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = UiHelpers.dp(this@OnboardingActivity, 12)
            })

            val defaultModel = settings.defaultModelName()
            val radius = UiHelpers.dpF(this, 14f)
            val inputLayout = TextInputLayout(this).apply {
                boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
                setBoxCornerRadii(radius, radius, radius, radius)
                boxStrokeColor = resolvedAccentColor()
                hint = "デバイス名"
                placeholderText = defaultModel
                isHelperTextEnabled = true
                helperText = "タップして自由に編集できます。\n空欄時は「$defaultModel」に設定されます。"
                endIconMode = TextInputLayout.END_ICON_CLEAR_TEXT
                findViewById<TextView>(com.google.android.material.R.id.textinput_helper_text)?.apply {
                    setLineSpacing(UiHelpers.dpF(this@OnboardingActivity, 2.5f), 1f)
                }
            }
            val edit = TextInputEditText(inputLayout.context).apply {
                isSingleLine = true
                imeOptions = EditorInfo.IME_ACTION_DONE
                setText(nicknameDraft)
                setSelection(text?.length ?: 0)
                doAfterTextChanged { editable ->
                    nicknameDraft = editable?.toString().orEmpty()
                }
            }
            nicknameEdit = edit
            inputLayout.addView(edit)
            nameCardBody.addView(inputLayout, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            group.addView(nameCardBody)
        })

        container.addView(buildSectionHeader("送信時の動作"))
        container.addView(buildGroupCard { group ->
            group.addView(buildSwitchRow(
                iconRes = R.drawable.ic_send_up,
                title = "送信後に自動で閉じる",
                subtitle = "送信完了後にシートを自動で閉じます",
                checked = settings.autoCloseAfterSend
            ) { settings.autoCloseAfterSend = it })

            group.addView(buildInsetDivider())
            group.addView(buildSwitchRow(
                iconRes = R.drawable.ic_volume,
                title = "効果音",
                subtitle = "NFC 接触や転送完了時にサウンドを再生します",
                checked = settings.soundEffectsEnabled
            ) { settings.soundEffectsEnabled = it })
        })

        return container
    }

    // ── Step 3: 権限の許可 ＆ 準備確認 ──
    private fun buildStep3View(): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        container.addView(buildStepTitle("権限の許可と準備"))
        container.addView(buildStepSubtitle("スムーズに送受信できるよう、\n必要な権限と NFC の状態を確認します。"))

        container.addView(buildSectionHeader("必要な権限"))
        container.addView(buildGroupCard { group ->
            if (Build.VERSION.SDK_INT >= 33) {
                val nearbyRow = buildPermissionExplainRow(
                    iconRes = R.drawable.ic_wifi_signal,
                    title = "付近のデバイス",
                    description = "写真やファイルを Wi-Fi Direct で高速に送受信するために必要です。\n（位置情報は使用しません）"
                )
                wifiPermBadge = nearbyRow.second
                group.addView(nearbyRow.first)

                group.addView(buildInsetDivider())
                val notifRow = buildPermissionExplainRow(
                    iconRes = R.drawable.ic_bell,
                    title = "通知",
                    description = "バックグラウンド転送中の進捗表示や\nキャンセル操作に必要です。"
                )
                notifPermBadge = notifRow.second
                group.addView(notifRow.first)
            } else {
                val locRow = buildPermissionExplainRow(
                    iconRes = R.drawable.ic_wifi_signal,
                    title = "位置情報",
                    description = "Android 12 以前では、Wi-Fi Direct で近くの端末と\n直接接続するためにシステム上必要です。"
                )
                wifiPermBadge = locRow.second
                group.addView(locRow.first)
            }

            group.addView(buildInsetDivider())

            val btnWrap = FrameLayout(this).apply {
                val hp = UiHelpers.dp(this@OnboardingActivity, 16)
                val vp = UiHelpers.dp(this@OnboardingActivity, 14)
                setPadding(hp, vp, hp, vp)
            }
            val permBtn = MaterialButton(this).apply {
                textSize = 14.5f
                setTypeface(typeface, Typeface.BOLD)
                isAllCaps = false
                cornerRadius = UiHelpers.dp(this@OnboardingActivity, 999)
                elevation = 0f
                stateListAnimator = null
                UiHelpers.applyPressScale(this, 0.97f)
                setOnClickListener {
                    HapticFeedbackEngine.playUiTap(this@OnboardingActivity)
                    onPermissionActionClicked()
                }
            }
            permissionButton = permBtn
            btnWrap.addView(permBtn, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                UiHelpers.dp(this, 46)
            ))
            group.addView(btnWrap)
        })

        // NFC Off Warning Card (shown only when NFC is off)
        val nfcCard = MaterialCardView(this).apply {
            radius = UiHelpers.dpF(this@OnboardingActivity, 22f)
            cardElevation = 0f
            strokeWidth = UiHelpers.dp(this@OnboardingActivity, 1)
            strokeColor = UiHelpers.color(this@OnboardingActivity, R.color.accent_amber)
            setCardBackgroundColor(UiHelpers.color(this@OnboardingActivity, R.color.surface_card))
            visibility = View.GONE
        }
        val nfcRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val hp = UiHelpers.dp(this@OnboardingActivity, 16)
            val vp = UiHelpers.dp(this@OnboardingActivity, 14)
            setPadding(hp, vp, hp, vp)
        }
        val amberCol = UiHelpers.color(this, R.color.accent_amber)
        val nfcIconWrap = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = UiHelpers.dpF(this@OnboardingActivity, 10f)
                setColor(UiHelpers.color(this@OnboardingActivity, R.color.surface_subtle))
            }
        }
        val nfcIcon = ImageView(this).apply {
            setImageDrawable(UiHelpers.tintIcon(this@OnboardingActivity, R.drawable.ic_nfc_waves, amberCol))
        }
        nfcIconWrap.addView(nfcIcon, FrameLayout.LayoutParams(
            UiHelpers.dp(this, 19),
            UiHelpers.dp(this, 19),
            Gravity.CENTER
        ))
        nfcRow.addView(nfcIconWrap, LinearLayout.LayoutParams(
            UiHelpers.dp(this, 36),
            UiHelpers.dp(this, 36)
        ).apply {
            marginEnd = UiHelpers.dp(this@OnboardingActivity, 12)
        })

        val nfcTextCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        nfcTextCol.addView(TextView(this).apply {
            text = "NFC がオフです"
            textSize = 14.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(UiHelpers.color(this@OnboardingActivity, R.color.text_primary))
        })
        nfcTextCol.addView(TextView(this).apply {
            text = "かざして共有するには端末の NFC をオンにしてください"
            textSize = 12.5f
            setTextColor(UiHelpers.color(this@OnboardingActivity, R.color.text_secondary))
            setPadding(0, UiHelpers.dp(this@OnboardingActivity, 2), 0, 0)
        })
        nfcRow.addView(nfcTextCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = UiHelpers.dp(this@OnboardingActivity, 10)
        })

        val openNfcBtn = TextView(this).apply {
            text = "設定を開く"
            textSize = 12.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            val php = UiHelpers.dp(this@OnboardingActivity, 12)
            val pvp = UiHelpers.dp(this@OnboardingActivity, 7)
            setPadding(php, pvp, php, pvp)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = UiHelpers.dpF(this@OnboardingActivity, 999f)
                setColor(amberCol)
            }
            isClickable = true
            isFocusable = true
            UiHelpers.applyPressScale(this, 0.95f)
            setOnClickListener {
                HapticFeedbackEngine.playUiTap(this@OnboardingActivity)
                runCatching { startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }
            }
        }
        nfcRow.addView(openNfcBtn)
        nfcCard.addView(nfcRow)
        nfcWarningCard = nfcCard
        container.addView(nfcCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = UiHelpers.dp(this@OnboardingActivity, 14)
        })

        val deferHint = TextView(this).apply {
            text = "権限や NFC はあとから設定することもできます。"
            textSize = 12.5f
            gravity = Gravity.CENTER
            setLineSpacing(UiHelpers.dpF(this@OnboardingActivity, 2.5f), 1f)
            setTextColor(UiHelpers.color(this@OnboardingActivity, R.color.text_tertiary))
            setPadding(0, UiHelpers.dp(this@OnboardingActivity, 16), 0, 0)
        }
        container.addView(deferHint)

        return container
    }

    // ── Step 4: さあ、始めましょう ──
    private fun buildStep4View(): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        container.addView(buildStepTitle("さあ、始めましょう！"))
        container.addView(buildStepSubtitle("準備が整いました。\nいつでもすぐにファイルやリンクを共有できます。"))

        container.addView(buildSectionHeader("設定タブでできること"))
        container.addView(buildGroupCard { group ->
            group.addView(buildInfoRow(
                iconRes = R.drawable.ic_photo_library,
                title = "受信時の自動アクション",
                description = "画像・動画・リンクを受信したときに自動で開く・コピーする設定や、使うアプリを選べます。"
            ))
            group.addView(buildInsetDivider())
            group.addView(buildInfoRow(
                iconRes = R.drawable.ic_folder,
                title = "保存先フォルダのカスタマイズ",
                description = "受信したファイルの保存先を、標準の Download/ から好きなフォルダへ変更できます。"
            ))
            group.addView(buildInsetDivider())
            group.addView(buildInfoRow(
                iconRes = R.drawable.ic_wifi_signal,
                title = "通信・動作の細かい調整",
                description = "同一 Wi-Fi 優先の切り替えや、20MB P2P 速度計測ツールなどを利用できます。"
            ))
        })

        val settingsHint = TextView(this).apply {
            text = "これらの設定は、ホーム画面下の「設定」タブから\nいつでも細かく調整できます。"
            textSize = 12.5f
            gravity = Gravity.CENTER
            setLineSpacing(UiHelpers.dpF(this@OnboardingActivity, 2.5f), 1f)
            setTextColor(UiHelpers.color(this@OnboardingActivity, R.color.text_tertiary))
            setPadding(0, UiHelpers.dp(this@OnboardingActivity, 16), 0, 0)
        }
        container.addView(settingsHint)

        return container
    }

    private fun onPermissionActionClicked() {
        val missing = UiHelpers.missingAllRecommendedPermissions(this)
        if (missing.isEmpty()) return
        if (permissionPermanentlyDenied) {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            runCatching { startActivity(intent) }
        } else {
            permissionLauncher.launch(missing)
        }
    }

    private fun refreshStep3Status() {
        val emerald = UiHelpers.color(this, R.color.accent_emerald)
        val subtle = UiHelpers.color(this, R.color.surface_subtle)
        val secondary = UiHelpers.color(this, R.color.text_secondary)

        if (Build.VERSION.SDK_INT >= 33) {
            val nearbyGranted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED
            val notifGranted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            wifiPermBadge?.let { applyPermissionBadge(it, nearbyGranted, emerald, subtle, secondary) }
            notifPermBadge?.let { applyPermissionBadge(it, notifGranted, emerald, subtle, secondary) }
        } else {
            val fineGranted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            val coarseGranted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            wifiPermBadge?.let {
                applyPermissionBadge(it, fineGranted && coarseGranted, emerald, subtle, secondary)
            }
        }

        val missing = UiHelpers.missingAllRecommendedPermissions(this)
        if (missing.isEmpty()) {
            permissionPermanentlyDenied = false
        }
        permissionButton?.apply {
            if (missing.isEmpty()) {
                text = "すべて許可済み"
                isEnabled = false
                setBackgroundColor(subtle)
                setTextColor(emerald)
                icon = UiHelpers.tintIcon(this@OnboardingActivity, R.drawable.ic_check_circle, emerald)
                iconTint = ColorStateList.valueOf(emerald)
                iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            } else if (permissionPermanentlyDenied) {
                text = "アプリ設定で権限を開く"
                isEnabled = true
                setBackgroundColor(resolvedAccentColor())
                setTextColor(Color.WHITE)
                icon = null
            } else {
                text = "権限を許可する"
                isEnabled = true
                setBackgroundColor(resolvedAccentColor())
                setTextColor(Color.WHITE)
                icon = null
            }
        }

        val nfcEnabled = NfcAdapter.getDefaultAdapter(this)?.isEnabled == true
        nfcWarningCard?.visibility = if (nfcEnabled) View.GONE else View.VISIBLE
    }

    private fun applyPermissionBadge(
        badge: TextView,
        granted: Boolean,
        emerald: Int,
        subtle: Int,
        secondary: Int
    ) {
        badge.text = if (granted) "許可済み" else "未許可"
        badge.setTextColor(if (granted) Color.WHITE else secondary)
        badge.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = UiHelpers.dpF(this@OnboardingActivity, 999f)
            setColor(if (granted) emerald else subtle)
        }
    }

    private fun showStep(step: Int, animate: Boolean, forward: Boolean) {
        val target = step.coerceIn(0, TOTAL_STEPS - 1)
        currentStep = target

        val captions = listOf("ようこそ・外観", "デバイス・送信", "権限と準備", "準備完了")
        stepCounterText.text = "ステップ ${target + 1} / $TOTAL_STEPS"
        stepCaptionText.text = captions[target]

        val accent = resolvedAccentColor()
        val inactive = UiHelpers.color(this, R.color.surface_subtle)
        progressSegments.forEachIndexed { idx, seg ->
            seg.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = UiHelpers.dpF(this@OnboardingActivity, 999f)
                setColor(if (idx <= target) accent else inactive)
            }
        }

        val shift = UiHelpers.dpF(this, 24f) * if (forward) 1f else -1f
        stepViews.forEachIndexed { idx, view ->
            if (idx == target) {
                view.animate().cancel()
                view.visibility = View.VISIBLE
                if (animate) {
                    view.alpha = 0f
                    view.translationX = shift
                    view.animate()
                        .alpha(1f)
                        .translationX(0f)
                        .setDuration(220L)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                } else {
                    view.alpha = 1f
                    view.translationX = 0f
                }
            } else {
                view.animate().cancel()
                view.visibility = View.GONE
            }
        }

        backButton.visibility = if (target > 0) View.VISIBLE else View.GONE
        val nextLp = nextButton.layoutParams as LinearLayout.LayoutParams
        nextLp.weight = if (target > 0) 1.6f else 1f
        nextButton.layoutParams = nextLp
        nextButton.text = if (target == TOTAL_STEPS - 1) "はじめる" else "次へ"

        contentScroll.scrollTo(0, 0)
    }

    private fun onNextClicked() {
        when (currentStep) {
            0 -> showStep(1, animate = true, forward = true)
            1 -> {
                persistStep2Settings()
                hideKeyboard()
                showStep(2, animate = true, forward = true)
                refreshStep3Status()
            }
            2 -> showStep(3, animate = true, forward = true)
            else -> {
                persistStep2Settings()
                settings.onboardingCompleted = true
                setResult(RESULT_OK)
                finish()
            }
        }
    }

    private fun persistStep2Settings() {
        nicknameEdit?.text?.toString()?.let { nicknameDraft = it }
        val trimmed = nicknameDraft.trim()
        settings.deviceNickname = if (trimmed.isEmpty() || trimmed == settings.defaultModelName()) {
            null
        } else {
            trimmed
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        val focusView = currentFocus ?: nicknameEdit ?: return
        imm.hideSoftInputFromWindow(focusView.windowToken, 0)
    }

    // ── Shared UI Builders (HomeTabView / SettingsTabView style) ──

    private fun buildFeatureChip(label: String): TextView = TextView(this).apply {
        text = label
        textSize = 12f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(UiHelpers.color(this@OnboardingActivity, R.color.text_secondary))
        val hp = UiHelpers.dp(this@OnboardingActivity, 12)
        val vp = UiHelpers.dp(this@OnboardingActivity, 6)
        setPadding(hp, vp, hp, vp)
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = UiHelpers.dpF(this@OnboardingActivity, 999f)
            setColor(UiHelpers.color(this@OnboardingActivity, R.color.surface_card))
        }
    }

    private fun buildStepTitle(title: String): TextView = TextView(this).apply {
        text = title
        textSize = 24f
        setTypeface(typeface, Typeface.BOLD)
        letterSpacing = -0.025f
        setTextColor(UiHelpers.color(this@OnboardingActivity, R.color.text_primary))
        setPadding(
            UiHelpers.dp(this@OnboardingActivity, 4),
            UiHelpers.dp(this@OnboardingActivity, 6),
            UiHelpers.dp(this@OnboardingActivity, 4),
            UiHelpers.dp(this@OnboardingActivity, 6)
        )
    }

    private fun buildStepSubtitle(subtitle: String): TextView = TextView(this).apply {
        text = subtitle
        textSize = 14f
        setLineSpacing(UiHelpers.dpF(this@OnboardingActivity, 3.5f), 1f)
        setTextColor(UiHelpers.color(this@OnboardingActivity, R.color.text_secondary))
        setPadding(
            UiHelpers.dp(this@OnboardingActivity, 4),
            0,
            UiHelpers.dp(this@OnboardingActivity, 4),
            UiHelpers.dp(this@OnboardingActivity, 8)
        )
    }

    private fun buildSectionHeader(title: String): TextView = TextView(this).apply {
        text = title
        textSize = 13f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(UiHelpers.color(this@OnboardingActivity, R.color.text_secondary))
        setPadding(
            UiHelpers.dp(this@OnboardingActivity, 8),
            UiHelpers.dp(this@OnboardingActivity, 16),
            0,
            UiHelpers.dp(this@OnboardingActivity, 8)
        )
    }

    private fun buildGroupCard(populate: (LinearLayout) -> Unit): MaterialCardView {
        val card = MaterialCardView(this).apply {
            radius = UiHelpers.dpF(this@OnboardingActivity, 22f)
            cardElevation = 0f
            strokeWidth = 0
            setCardBackgroundColor(UiHelpers.color(this@OnboardingActivity, R.color.surface_card))
        }
        val stack = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        populate(stack)
        card.addView(stack)
        return card
    }

    private fun buildInsetDivider(): View = View(this).apply {
        setBackgroundColor(UiHelpers.color(this@OnboardingActivity, R.color.divider_inset))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
            marginStart = UiHelpers.dp(this@OnboardingActivity, 60)
            marginEnd = UiHelpers.dp(this@OnboardingActivity, 16)
        }
    }

    private fun buildLeadingIcon(@DrawableRes iconRes: Int): FrameLayout {
        val badge = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = UiHelpers.dpF(this@OnboardingActivity, 9f)
                setColor(UiHelpers.color(this@OnboardingActivity, R.color.surface_subtle))
            }
        }
        val icon = ImageView(this).apply {
            setImageDrawable(UiHelpers.tintIcon(
                this@OnboardingActivity,
                iconRes,
                UiHelpers.color(this@OnboardingActivity, R.color.text_primary)
            ))
        }
        badge.addView(icon, FrameLayout.LayoutParams(
            UiHelpers.dp(this, 18),
            UiHelpers.dp(this, 18),
            Gravity.CENTER
        ))
        badge.layoutParams = LinearLayout.LayoutParams(
            UiHelpers.dp(this, 32),
            UiHelpers.dp(this, 32)
        ).apply {
            marginEnd = UiHelpers.dp(this@OnboardingActivity, 12)
        }
        return badge
    }

    private fun createOemSwitch(checked: Boolean, onChanged: (Boolean) -> Unit): MaterialSwitch {
        val accent = resolvedAccentColor()
        val trackOff = UiHelpers.color(this, R.color.surface_subtle)
        val thumbOff = UiHelpers.color(this, R.color.text_tertiary)
        return MaterialSwitch(this).apply {
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
                HapticFeedbackEngine.playUiTap(this@OnboardingActivity)
                onChanged(isChecked)
            }
        }
    }

    private fun buildSwitchRow(
        @DrawableRes iconRes: Int,
        title: String,
        subtitle: String,
        checked: Boolean,
        onChanged: (Boolean) -> Unit
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val hp = UiHelpers.dp(this@OnboardingActivity, 16)
            val vp = UiHelpers.dp(this@OnboardingActivity, 13)
            setPadding(hp, vp, hp, vp)
            isClickable = true
            isFocusable = true
        }
        row.addView(buildLeadingIcon(iconRes))

        val textCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        textCol.addView(TextView(this).apply {
            text = title
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(UiHelpers.color(this@OnboardingActivity, R.color.text_primary))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        textCol.addView(TextView(this).apply {
            text = subtitle
            textSize = 12.5f
            setTextColor(UiHelpers.color(this@OnboardingActivity, R.color.text_secondary))
            setPadding(0, UiHelpers.dp(this@OnboardingActivity, 2), 0, 0)
        })

        row.addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = UiHelpers.dp(this@OnboardingActivity, 12)
        })

        val switchView = createOemSwitch(checked, onChanged)
        row.setOnClickListener { switchView.toggle() }
        row.addView(switchView)
        return row
    }

    private fun buildInfoRow(
        @DrawableRes iconRes: Int,
        title: String,
        description: String
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val hp = UiHelpers.dp(this@OnboardingActivity, 16)
            val vp = UiHelpers.dp(this@OnboardingActivity, 15)
            setPadding(hp, vp, hp, vp)
        }
        row.addView(buildLeadingIcon(iconRes))

        val textCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        textCol.addView(TextView(this).apply {
            text = title
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(UiHelpers.color(this@OnboardingActivity, R.color.text_primary))
        })
        textCol.addView(TextView(this).apply {
            text = description
            textSize = 12.5f
            setLineSpacing(UiHelpers.dpF(this@OnboardingActivity, 2.5f), 1f)
            setTextColor(UiHelpers.color(this@OnboardingActivity, R.color.text_secondary))
            setPadding(0, UiHelpers.dp(this@OnboardingActivity, 3), 0, 0)
        })
        row.addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun buildPermissionExplainRow(
        @DrawableRes iconRes: Int,
        title: String,
        description: String
    ): Pair<LinearLayout, TextView> {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val hp = UiHelpers.dp(this@OnboardingActivity, 16)
            val vp = UiHelpers.dp(this@OnboardingActivity, 15)
            setPadding(hp, vp, hp, vp)
        }
        row.addView(buildLeadingIcon(iconRes))

        val textCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        textCol.addView(TextView(this).apply {
            text = title
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(UiHelpers.color(this@OnboardingActivity, R.color.text_primary))
        })
        textCol.addView(TextView(this).apply {
            text = description
            textSize = 12.5f
            setLineSpacing(UiHelpers.dpF(this@OnboardingActivity, 2.5f), 1f)
            setTextColor(UiHelpers.color(this@OnboardingActivity, R.color.text_secondary))
            setPadding(0, UiHelpers.dp(this@OnboardingActivity, 3), 0, 0)
        })
        row.addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = UiHelpers.dp(this@OnboardingActivity, 10)
        })

        val badge = TextView(this).apply {
            textSize = 11.5f
            setTypeface(typeface, Typeface.BOLD)
            val php = UiHelpers.dp(this@OnboardingActivity, 10)
            val pvp = UiHelpers.dp(this@OnboardingActivity, 5)
            setPadding(php, pvp, php, pvp)
        }
        row.addView(badge)
        return row to badge
    }
}
