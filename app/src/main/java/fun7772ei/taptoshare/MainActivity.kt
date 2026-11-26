package fun7772ei.taptoshare

import android.app.DownloadManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.nfc.NfcAdapter
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import fun7772ei.taptoshare.ui.ClipboardPreview
import fun7772ei.taptoshare.ui.FloatingGlassNavBar
import fun7772ei.taptoshare.ui.GlassNavItem
import fun7772ei.taptoshare.ui.HistoryTabCallbacks
import fun7772ei.taptoshare.ui.HistoryTabView
import fun7772ei.taptoshare.ui.HomeTabCallbacks
import fun7772ei.taptoshare.ui.HomeTabView
import fun7772ei.taptoshare.ui.SettingsTabCallbacks
import fun7772ei.taptoshare.ui.SettingsTabView

class MainActivity : AppCompatActivity() {

    companion object {
        private const val NAV_HOME = 1
        private const val NAV_HISTORY = 2
        private const val NAV_SETTINGS = 3
        private const val STATE_SELECTED_TAB = "selected_tab"
        private const val STATE_ONBOARDING_LAUNCHED = "onboarding_launched"
    }

    private lateinit var settings: AppSettings
    private lateinit var historyStore: TransferHistoryStore

    private lateinit var tabHost: FrameLayout
    private lateinit var floatingNav: FloatingGlassNavBar
    private lateinit var homeTab: HomeTabView
    private lateinit var historyTab: HistoryTabView
    private lateinit var settingsTab: SettingsTabView

    private var activeTabId = NAV_HOME
    private var onboardingLaunched = false
    private lateinit var appliedThemeMode: ThemeMode
    private var appliedDynamicColor = false

    private val onboardingLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        onboardingLaunched = false
        if (result.resultCode == RESULT_OK || settings.onboardingCompleted) {
            if (settings.dynamicColorEnabled != appliedDynamicColor) {
                recreate()
            } else {
                refreshAllUi()
            }
        } else {
            finish()
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshAllUi()
    }

    private val pickMediaLauncher = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (!uris.isNullOrEmpty()) {
            launchShareUris(ArrayList(uris), null)
        }
    }

    private val pickFilesLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (!uris.isNullOrEmpty()) {
            launchShareUris(ArrayList(uris), null)
        }
    }

    private val sendFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            prepareAndSendFolder(treeUri)
        }
    }

    private val destFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { contentResolver.takePersistableUriPermission(treeUri, flags) }
            settings.customTreeUriString = treeUri.toString()
            refreshAllUi()
            settingsTab.refreshDestinationLabel()
            Toast.makeText(this, "保存先を変更しました", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = AppSettings(this)
        appliedThemeMode = settings.themeMode
        appliedDynamicColor = settings.dynamicColorEnabled
        historyStore = TransferHistoryStore(this)
        activeTabId = savedInstanceState?.getInt(STATE_SELECTED_TAB, NAV_HOME) ?: NAV_HOME
        onboardingLaunched = savedInstanceState?.getBoolean(STATE_ONBOARDING_LAUNCHED, false) ?: false

        UiHelpers.configureEdgeToEdgeBars(this)
        buildShellUi()

        val specialLaunch = isSpecialLaunch(intent)
        if (!specialLaunch && !settings.onboardingCompleted) {
            if (!onboardingLaunched) {
                onboardingLaunched = true
                onboardingLauncher.launch(Intent(this, OnboardingActivity::class.java))
            }
        } else {
            if (savedInstanceState == null && !specialLaunch) {
                val missing = UiHelpers.missingAllRecommendedPermissions(this)
                if (missing.isNotEmpty()) {
                    permissionLauncher.launch(missing)
                }
            }
            handleUtilityIntent(intent)
        }
    }

    private fun isSpecialLaunch(intent: Intent?): Boolean {
        return !intent?.getStringExtra("tab").isNullOrBlank()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_SELECTED_TAB, activeTabId)
        outState.putBoolean(STATE_ONBOARDING_LAUNCHED, onboardingLaunched)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleUtilityIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshAllUi()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && settings.onboardingCompleted && !onboardingLaunched) {
            homeTab.updateClipboardPreview(readClipboardPreview())
        }
    }

    private fun handleUtilityIntent(intent: Intent?) {
        when (intent?.getStringExtra("tab")) {
            "home" -> selectTab(NAV_HOME)
            "history" -> selectTab(NAV_HISTORY)
            "settings" -> selectTab(NAV_SETTINGS)
        }
    }

    private fun buildShellUi() {
        val bgCol = UiHelpers.color(this, R.color.bg_canvas)
        val root = FrameLayout(this).apply {
            setBackgroundColor(bgCol)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // 1. Full-bleed tab host (content scrolls smoothly behind the floating dock)
        tabHost = FrameLayout(this)

        homeTab = HomeTabView(this, lifecycleScope, settings, object : HomeTabCallbacks {
            override fun onPlaySendDemo() = launchSendDemo()
            override fun onPlayReceiveDemo() = launchReceiveDemo()
            override fun onPickPhotosAndVideos() {
                runCatching {
                    pickMediaLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                }.onFailure {
                    pickFilesLauncher.launch(arrayOf("image/*", "video/*"))
                }
            }
            override fun onPickFiles() = pickFilesLauncher.launch(arrayOf("*/*"))
            override fun onPickFolder() = sendFolderLauncher.launch(null)
            override fun onComposeTextOrUrl() = showTextComposerSheet()
            override fun onSendClipboard() = sendClipboard()
            override fun onSeeAllHistory() = selectTab(NAV_HISTORY)
            override fun onHistoryClick(entry: HistoryEntry) = openHistoryEntry(entry)
            override fun onOpenNfcSettings() {
                runCatching { startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }
            }
            override fun onOpenWifiPanel() = UiHelpers.openWifiPanel(this@MainActivity)
        })

        historyTab = HistoryTabView(this, lifecycleScope, object : HistoryTabCallbacks {
            override fun onHistoryOpen(entry: HistoryEntry) = openHistoryEntry(entry)
            override fun onHistoryResend(entry: HistoryEntry) = resendHistoryEntry(entry)
            override fun onHistoryDelete(entry: HistoryEntry) {
                historyStore.deleteById(entry.id)
                refreshHistory()
            }
            override fun onHistoryClearAll() {
                historyStore.clear()
                refreshHistory()
            }
        })

        settingsTab = SettingsTabView(this, settings, object : SettingsTabCallbacks {
            override fun onPlaySendDemo() = launchSendDemo()
            override fun onPlayReceiveDemo() = launchReceiveDemo()
            override fun onChooseDestinationFolder() = destFolderLauncher.launch(null)
            override fun onResetDestinationFolder() {
                settings.customTreeUriString = null
                refreshAllUi()
            }
            override fun onRunBenchmark() {
                startActivity(
                    InternalLaunchToken.attach(
                        Intent(this@MainActivity, ShareSheetActivity::class.java)
                            .putExtra(ShareSheetActivity.EXTRA_BENCHMARK_FORCE_P2P, true)
                    )
                )
            }
            override fun onDynamicColorChanged() = recreate()
            override fun onDeviceNicknameChanged() = refreshStatus()
        })

        tabHost.addView(homeTab, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        tabHost.addView(historyTab, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        tabHost.addView(settingsTab, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(tabHost, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // 2. Soft bottom gradient fade scrim beneath floating dock
        val bottomFade = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.TRANSPARENT, Color.argb(235, Color.red(bgCol), Color.green(bgCol), Color.blue(bgCol)))
            )
        }
        root.addView(bottomFade, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            UiHelpers.dp(this, 104),
            Gravity.BOTTOM
        ))

        // 4. Floating Pill Navigation Dock
        val dockWidth = minOf(
            resources.displayMetrics.widthPixels - UiHelpers.dp(this, 44),
            UiHelpers.dp(this, 340)
        )
        floatingNav = FloatingGlassNavBar(this).apply {
            configure(
                listOf(
                    GlassNavItem(NAV_HOME, "ホーム", R.drawable.ic_nav_home),
                    GlassNavItem(NAV_HISTORY, "履歴", R.drawable.ic_nav_history),
                    GlassNavItem(NAV_SETTINGS, "設定", R.drawable.ic_nav_settings)
                ),
                initialId = activeTabId
            ) { id ->
                showTabInternal(id)
            }
        }
        val navLp = FrameLayout.LayoutParams(dockWidth, UiHelpers.dp(this, 62), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        root.addView(floatingNav, navLp)

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            tabHost.setPadding(sys.left, sys.top, sys.right, 0)
            navLp.bottomMargin = sys.bottom + UiHelpers.dp(this, 14)
            floatingNav.layoutParams = navLp
            insets
        }

        setContentView(root)
        selectTab(activeTabId)
    }

    private fun selectTab(tabId: Int) {
        floatingNav.selectById(tabId, animate = true)
        showTabInternal(tabId)
    }

    private fun showTabInternal(tabId: Int) {
        activeTabId = tabId
        val views = listOf(NAV_HOME to homeTab, NAV_HISTORY to historyTab, NAV_SETTINGS to settingsTab)
        for ((id, view) in views) {
            if (id == tabId) {
                if (view.visibility != View.VISIBLE) {
                    view.alpha = 0f
                    view.translationY = UiHelpers.dpF(this, 8f)
                    view.visibility = View.VISIBLE
                    view.animate().alpha(1f).translationY(0f).setDuration(200L).start()
                }
            } else {
                view.visibility = View.GONE
            }
        }
    }

    private fun refreshAllUi() {
        refreshStatus()
        if (settings.onboardingCompleted && !onboardingLaunched) {
            homeTab.updateClipboardPreview(readClipboardPreview())
        }
        refreshHistory()
        settingsTab.syncFromSettings()
    }

    private fun refreshStatus() {
        val nfc = NfcAdapter.getDefaultAdapter(this)
        val nfcOk = nfc?.isEnabled == true
        val wifiOk = UiHelpers.isWifiEnabled(this)
        val wifiSnap = WifiStatusChecker.capture(this)
        homeTab.updateStatus(nfcOk, wifiOk, wifiSnap)
    }

    private fun refreshHistory() {
        val items = historyStore.getAll()
        homeTab.updateRecentHistory(items)
        historyTab.submitHistory(items)
    }

    private fun readClipboardPreview(): ClipboardPreview {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = runCatching { cm.primaryClip }.getOrNull()
        if (clip == null || clip.itemCount == 0) return ClipboardPreview.Empty
        val item = clip.getItemAt(0)
        val uri = item.uri
        if (uri != null) {
            if (UiHelpers.canReadUri(this, uri)) {
                val (name, _, _) = UiHelpers.queryUriMeta(this, uri)
                return ClipboardPreview.ContentUri(uri, name)
            }
            val fallbackText = item.text?.toString().orEmpty().trim()
            if (fallbackText.isEmpty()) return ClipboardPreview.Empty
            return if (ReceiveActionHandler.isSingleUrl(fallbackText)) {
                ClipboardPreview.Url(fallbackText)
            } else {
                ClipboardPreview.Text(fallbackText)
            }
        }
        val txt = item.coerceToText(this)?.toString().orEmpty().trim()
        if (txt.isEmpty()) return ClipboardPreview.Empty
        return if (ReceiveActionHandler.isSingleUrl(txt)) {
            ClipboardPreview.Url(txt)
        } else {
            ClipboardPreview.Text(txt)
        }
    }

    private fun sendClipboard() {
        when (val preview = readClipboardPreview()) {
            is ClipboardPreview.Empty -> Toast.makeText(this, "クリップボードが空です", Toast.LENGTH_SHORT).show()
            is ClipboardPreview.ContentUri -> launchShareUris(arrayListOf(preview.uri), null)
            is ClipboardPreview.Url -> launchShareText(preview.url)
            is ClipboardPreview.Text -> launchShareText(preview.text)
        }
    }

    private fun launchSendDemo() {
        startActivity(
            InternalLaunchToken.attach(
                Intent(this, ShareSheetActivity::class.java)
                    .putExtra(ShareSheetActivity.EXTRA_DEMO_MODE, true)
            )
        )
    }

    private fun launchReceiveDemo() {
        startActivity(
            InternalLaunchToken.attach(
                Intent(this, ReceiveActivity::class.java)
                    .putExtra(ReceiveActivity.EXTRA_DEMO_MODE, true)
            )
        )
    }

    private fun showTextComposerSheet() {
        val dialog = BottomSheetDialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val hp = UiHelpers.dp(this@MainActivity, 20)
            setPadding(hp, UiHelpers.dp(this@MainActivity, 20), hp, UiHelpers.dp(this@MainActivity, 24))
        }

        val inputLayout = TextInputLayout(this).apply {
            hint = "テキスト または URL を入力"
        }
        val edit = TextInputEditText(inputLayout.context).apply {
            minLines = 3
            maxLines = 6
        }
        inputLayout.addView(edit)
        root.addView(inputLayout, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = UiHelpers.dp(this@MainActivity, 14) })

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val pasteBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonTonalStyle).apply {
            text = "貼り付け"
            cornerRadius = UiHelpers.dp(this@MainActivity, 16)
            setOnClickListener {
                when (val clip = readClipboardPreview()) {
                    is ClipboardPreview.Url -> edit.setText(clip.url)
                    is ClipboardPreview.Text -> edit.setText(clip.text)
                    else -> Toast.makeText(this@MainActivity, "貼り付け可能なテキストがありません", Toast.LENGTH_SHORT).show()
                }
            }
        }
        val sendBtn = MaterialButton(this).apply {
            text = "送信"
            cornerRadius = UiHelpers.dp(this@MainActivity, 999)
            setBackgroundColor(UiHelpers.color(this@MainActivity, R.color.button_monochrome_fill))
            setTextColor(UiHelpers.color(this@MainActivity, R.color.button_monochrome_text))
            setOnClickListener {
                val txt = edit.text?.toString().orEmpty()
                if (txt.isBlank()) {
                    Toast.makeText(this@MainActivity, "テキストまたはURLを入力してください", Toast.LENGTH_SHORT).show()
                } else {
                    dialog.dismiss()
                    launchShareText(txt)
                }
            }
        }
        row.addView(pasteBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = UiHelpers.dp(this@MainActivity, 8)
        })
        row.addView(sendBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f))
        root.addView(row)

        dialog.setContentView(root)
        dialog.show()
    }

    private fun launchShareText(text: String) {
        startActivity(
            InternalLaunchToken.attach(
                Intent(this, ShareSheetActivity::class.java)
                    .putExtra(ShareSheetActivity.EXTRA_CUSTOM_TEXT, text)
            )
        )
    }

    private fun launchShareUris(uris: ArrayList<Uri>, relDirs: ArrayList<String>?) {
        val token = ShareSheetActivity.stashPendingUris(uris, relDirs)
        val intent = InternalLaunchToken.attach(
            Intent(this, ShareSheetActivity::class.java)
                .putExtra(ShareSheetActivity.EXTRA_PENDING_SHARE_TOKEN, token)
        )
        startActivity(intent)
    }

    private fun prepareAndSendFolder(treeUri: Uri) {
        val intent = InternalLaunchToken.attach(
            Intent(this, ShareSheetActivity::class.java)
                .putExtra(ShareSheetActivity.EXTRA_FOLDER_TREE_URI, treeUri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
        startActivity(intent)
    }

    private fun openHistoryEntry(entry: HistoryEntry) {
        if (!entry.textPayload.isNullOrBlank()) {
            if (entry.category == "URL") {
                ReceiveActionHandler.openUrl(this, settings, entry.textPayload)
                return
            } else if (entry.category == "TEXT" && entry.direction == "SEND") {
                ReceiveActionHandler.copyTextToClipboard(this, "Tap to Share Text", entry.textPayload)
                Toast.makeText(this, "テキストをクリップボードへコピーしました", Toast.LENGTH_SHORT).show()
                return
            }
        }
        val uriStr = entry.uriString
        if (uriStr == null) {
            Toast.makeText(this, entry.summary, Toast.LENGTH_SHORT).show()
            return
        }
        when (entry.category) {
            "URL" -> ReceiveActionHandler.openUrl(this, settings, uriStr)
            "TEXT" -> {
                val uri = Uri.parse(uriStr)
                val openIntent = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "text/plain")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                runCatching { startActivity(openIntent) }
                    .onFailure {
                        if (!entry.textPayload.isNullOrBlank()) {
                            ReceiveActionHandler.copyTextToClipboard(this, "Tap to Share Text", entry.textPayload)
                            Toast.makeText(this, "テキストをコピーしました", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this, entry.summary, Toast.LENGTH_SHORT).show()
                        }
                    }
            }
            "IMAGE", "VIDEO", "FILE" -> {
                if (entry.category == "FILE" && entry.fileCount > 1 && entry.direction == "RECEIVE") {
                    runCatching { startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)) }
                } else {
                    val saved = SavedFile(
                        name = entry.title,
                        relativeDir = "",
                        mime = entry.mimeType ?: "*/*",
                        size = entry.totalBytes,
                        uri = Uri.parse(uriStr),
                        displayPath = entry.summary
                    )
                    val cat = when (entry.category) {
                        "IMAGE" -> ReceiveCategory.IMAGE
                        "VIDEO" -> ReceiveCategory.VIDEO
                        else -> ReceiveCategory.OTHER
                    }
                    val ok = ReceiveActionHandler.openSavedFiles(this, settings, listOf(saved), cat)
                    if (!ok) {
                        Toast.makeText(this, "ファイルを開けませんでした（移動または削除された可能性があります）", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun resendHistoryEntry(entry: HistoryEntry) {
        if (!entry.textPayload.isNullOrBlank()) {
            launchShareText(entry.textPayload)
            return
        }
        val uriStr = entry.uriString
        if (!uriStr.isNullOrBlank()) {
            if (entry.category == "URL") {
                launchShareText(uriStr)
            } else {
                launchShareUris(arrayListOf(Uri.parse(uriStr)), null)
            }
            return
        }
        Toast.makeText(this, "再送信できるデータがありません", Toast.LENGTH_SHORT).show()
    }
}
