package fun7772ei.taptoshare

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.util.Log
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import fun7772ei.taptoshare.ui.HapticFeedbackEngine
import fun7772ei.taptoshare.ui.SoundEffectEngine
import fun7772ei.taptoshare.ui.ThumbnailLoader
import fun7772ei.taptoshare.ui.animation.StageAction
import fun7772ei.taptoshare.ui.animation.StageContent
import fun7772ei.taptoshare.ui.animation.TransferStageView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ShareSheetActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "TapToShare"
        private const val MAX_NFC_NDEF_BYTES = 28_000
        private const val IDLE_TIMEOUT_MS = 90_000L
        private const val MAX_FOLDER_FILES = 500
        private const val MAX_FOLDER_DEPTH = 16
        private const val STATE_INTENT_HANDLED = "state_intent_handled"

        const val EXTRA_CUSTOM_TEXT = "custom_text"
        const val EXTRA_CUSTOM_URIS = "custom_uris"
        const val EXTRA_RELATIVE_DIRS = "relative_dirs"
        const val EXTRA_FOLDER_TREE_URI = "folder_tree_uri"
        const val EXTRA_PENDING_SHARE_TOKEN = "pending_share_token"
        const val EXTRA_BENCHMARK_FORCE_P2P = "benchmark_force_p2p"
        const val EXTRA_DEMO_MODE = "demo_mode"

        private val pendingShareStore = ConcurrentHashMap<String, Pair<List<Uri>, List<String>?>>()

        fun stashPendingUris(uris: List<Uri>, relDirs: List<String>?): String {
            val token = UUID.randomUUID().toString()
            pendingShareStore[token] = uris.toList() to relDirs?.toList()
            return token
        }

        private fun consumePendingUris(token: String): Pair<List<Uri>, List<String>?>? {
            return pendingShareStore.remove(token)
        }
    }

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val autoCloseHandler = Handler(Looper.getMainLooper())

    private lateinit var settings: AppSettings
    private lateinit var historyStore: TransferHistoryStore
    private lateinit var nfcController: NfcBroadcastController
    private lateinit var p2pController: P2pController
    private lateinit var stage: TransferStageView

    private var activeServer: LocalFileServer? = null
    private var prepareJob: Job? = null
    private var setupJob: Job? = null
    private var idleTimeoutJob: Job? = null
    private var demoJob: Job? = null
    private var thumbnailJob: Job? = null

    private var intentHandled = false
    private var isClosing = false
    private var activeP2pGoIp: String? = null
    private var lastPayloadJson: String? = null
    private var lastPayloadSummary: String = ""
    private var isShortTextMode = false
    private var isDemoMode = false
    private var pendingFileAction: (() -> Unit)? = null
    private var pendingWifiRetry: (() -> Unit)? = null
    private var transferFinished = false

    private var activeCategoryLabel = "FILE"
    private var activeTitle = ""
    private var activeTotalBytes = 0L
    private var activeFileCount = 1
    private var activeLastSpeedMBps = 0.0
    private var activeLastRoute = "P2P"
    private var activeHistoryUriString: String? = null
    private var activeHistoryMime: String? = null
    private var activeHistoryTextPayload: String? = null

    private val cancelReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == TransferForegroundService.ACTION_CANCEL_BROADCAST) {
                cancelAndClose()
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val pending = pendingFileAction
        pendingFileAction = null
        if (UiHelpers.missingWifiPermissions(this).isEmpty()) {
            pending?.invoke()
        } else {
            val permLabel = if (Build.VERSION.SDK_INT >= 33) "付近のデバイス" else "位置情報"
            showError("Wi-Fi Direct 用の権限（$permLabel）が拒否されました")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        settings = AppSettings(this)
        historyStore = TransferHistoryStore(this)
        p2pController = P2pController(this)
        nfcController = NfcBroadcastController(this) { msg ->
            Log.d(TAG, msg)
        }

        UiHelpers.configureEdgeToEdgeBars(this, darkBackground = true)
        UiHelpers.applyWindowBlur(window, true, 26)

        stage = TransferStageView(
            context = this,
            isReceiver = false,
            onBlurRadiusChanged = { radius ->
                UiHelpers.applyWindowBlur(window, radius > 0, radius)
            },
            onBackdropTap = {
                if (!nfcController.isBroadcasting && (transferFinished || isDemoMode)) {
                    finishAnimated()
                }
            }
        )
        setContentView(stage)
        stage.playEntrance()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                cancelAndClose()
            }
        })

        ContextCompat.registerReceiver(
            this,
            cancelReceiver,
            IntentFilter(TransferForegroundService.ACTION_CANCEL_BROADCAST),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        nfcController.onReadFinished = { _ ->
            if (isShortTextMode && !transferFinished) {
                onShortTextSent()
            } else if (!transferFinished) {
                HapticFeedbackEngine.playNfcContact(this)
                SoundEffectEngine.playContactShimmer(this)
                stage.onTransferContact(
                    pillText = "NFC読取完了 • 相手からの接続待機中...",
                    detail = "相手端末が受信を開始しています（他アプリの誤起動を防ぐため、NFC待機を一時停止しました）",
                    routeTag = "NFC"
                )
                stage.configureActions(
                    secondary = StageAction("もう一度") { rearmNfc() },
                    closeText = "キャンセル",
                    onClose = { cancelAndClose() }
                )
            }
        }

        intentHandled = savedInstanceState?.getBoolean(STATE_INTENT_HANDLED, false) ?: false
        if (!intentHandled) {
            intentHandled = true
            handleIntent(intent)
        } else {
            finish()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_INTENT_HANDLED, intentHandled)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intentHandled = true
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        val retry = pendingWifiRetry
        if (retry != null && UiHelpers.isWifiEnabled(this) && !UiHelpers.isLocationRequiredAndDisabled(this)) {
            pendingWifiRetry = null
            retry()
        }
        if (!isDemoMode) {
            nfcController.onResume()
        }
    }

    override fun onPause() {
        if (!isDemoMode) {
            nfcController.onPause()
        }
        super.onPause()
    }

    override fun onDestroy() {
        autoCloseHandler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(cancelReceiver) }
        demoJob?.cancel()
        thumbnailJob?.cancel()
        prepareJob?.cancel()
        setupJob?.cancel()
        idleTimeoutJob?.cancel()
        activeServer?.stop()
        nfcController.release()
        TransferForegroundService.stop(this)
        uiScope.cancel()
        val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        cleanupScope.launch {
            runCatching { p2pController.cleanupGroup() }
            p2pController.close()
            cleanupScope.cancel()
        }
        super.onDestroy()
    }

    private fun disableWindowTransitions() {
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) {
            disableWindowTransitions()
            finishAndRemoveTask()
            return
        }

        val isInternalLaunch = InternalLaunchToken.consume(intent)
        if (isInternalLaunch) {
            if (intent.getBooleanExtra(EXTRA_DEMO_MODE, false)) {
                isDemoMode = true
                runSendDemo()
                return
            }
            isDemoMode = false

            if (intent.getBooleanExtra(EXTRA_BENCHMARK_FORCE_P2P, false)) {
                val benchItem = SharedItem(
                    name = LocalFileServer.BENCHMARK_NAME,
                    size = LocalFileServer.BENCHMARK_SIZE_BYTES,
                    mime = "application/octet-stream",
                    syntheticBytes = LocalFileServer.BENCHMARK_SIZE_BYTES
                )
                startFileBroadcast(listOf(benchItem), forceP2p = true, isLargeText = false, labelOverride = "20MB P2P 診断テスト")
                return
            }

            val customText = intent.getStringExtra(EXTRA_CUSTOM_TEXT)
            if (!customText.isNullOrBlank()) {
                startTextBroadcast(customText)
                return
            }

            val pendingToken = intent.getStringExtra(EXTRA_PENDING_SHARE_TOKEN)
            if (!pendingToken.isNullOrBlank()) {
                val stashed = consumePendingUris(pendingToken)
                if (stashed != null && stashed.first.isNotEmpty()) {
                    prepareAndStartUris(stashed.first, stashed.second)
                } else {
                    showError("共有対象のデータが見つかりませんでした")
                }
                return
            }

            val folderTreeUri = getParcelableExtraCompat(intent, EXTRA_FOLDER_TREE_URI)
            if (folderTreeUri != null) {
                prepareAndStartFolderTree(folderTreeUri)
                return
            }

            val customUris: ArrayList<Uri>? = getParcelableListExtra(intent, EXTRA_CUSTOM_URIS)
            if (!customUris.isNullOrEmpty()) {
                val relDirs = intent.getStringArrayListExtra(EXTRA_RELATIVE_DIRS)
                prepareAndStartUris(customUris, relDirs)
                return
            }
        }
        isDemoMode = false

        when (intent.action) {
            Intent.ACTION_SEND -> {
                val streamUri = getParcelableExtraCompat(intent, Intent.EXTRA_STREAM)
                val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (streamUri != null) {
                    prepareAndStartUris(listOf(streamUri), null)
                } else if (!sharedText.isNullOrBlank()) {
                    startTextBroadcast(sharedText)
                } else {
                    showError("共有できるデータが見つかりませんでした")
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = getParcelableListExtra(intent, Intent.EXTRA_STREAM)
                if (!uris.isNullOrEmpty()) {
                    prepareAndStartUris(uris, null)
                } else {
                    val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                    if (!sharedText.isNullOrBlank()) {
                        startTextBroadcast(sharedText)
                    } else {
                        showError("共有できるファイルが見つかりませんでした")
                    }
                }
            }
            else -> showError("無効な起動パラメータです")
        }
    }

    private fun runSendDemo() {
        demoJob?.cancel()
        cleanupActiveTransfer()
        transferFinished = false

        stage.setPeerSubtitle(settings.effectiveDeviceName())
        stage.setContent(
            StageContent.Media(
                title = "Aurora_Twilight_01.jpg",
                subtitle = "3.84 MB",
                bitmap = ThumbnailLoader.createDemoArtworkBitmap()
            )
        )
        stage.setPhaseReady(
            pillText = "相手端末の背面をかざしてください",
            detail = "",
            routeTag = "DEMO"
        )
        stage.configureActions(closeText = "閉じる") { cancelAndClose() }
        HapticFeedbackEngine.playReadyTick(this)

        demoJob = uiScope.launch {
            delay(850L)
            HapticFeedbackEngine.playNfcContact(this@ShareSheetActivity)
            SoundEffectEngine.playContactShimmer(this@ShareSheetActivity)
            stage.onTransferContact(
                pillText = "相手端末を検出しました",
                detail = "",
                routeTag = "P2P 5GHz"
            )
            delay(360L)

            val steps = 25
            val demoTotalBytes = 4_026_532L
            for (i in 1..steps) {
                val frac = i.toFloat() / steps
                val speed = 48.0 + (i % 5) * 2.4
                val bytesSent = (demoTotalBytes * frac).toLong()
                stage.updateProgress(
                    fraction = frac,
                    speedText = String.format(Locale.US, "%.1f MB/s", speed),
                    fileLabel = "1/1: Aurora_Twilight_01.jpg • ${UiHelpers.formatBytes(bytesSent)} / 3.84 MB",
                    routeTag = "P2P 5GHz"
                )
                delay(52L)
            }

            transferFinished = true
            HapticFeedbackEngine.playTransferComplete(this@ShareSheetActivity)
            SoundEffectEngine.playCompleteBloom(this@ShareSheetActivity)
            stage.onComplete(
                pillText = "送信完了",
                detail = "",
                routeTag = "P2P 5GHz",
                speedSubtitle = "58.4 MB/s • 3.84 MB"
            )
            stage.configureActions(
                primary = StageAction("もう一度") { runSendDemo() },
                closeText = "閉じる",
                onClose = { cancelAndClose() }
            )
        }
    }

    private fun startTextBroadcast(rawText: String) {
        cleanupActiveTransfer()
        transferFinished = false
        val bytes = rawText.toByteArray(Charsets.UTF_8)
        val isUrl = ReceiveActionHandler.isSingleUrl(rawText)
        activeCategoryLabel = if (isUrl) "URL" else "TEXT"
        activeTitle = if (isUrl) rawText.trim() else rawText.trim().lineSequence().firstOrNull().orEmpty().take(48)
        activeTotalBytes = bytes.size.toLong()
        activeFileCount = 1
        activeHistoryUriString = if (isUrl) rawText.trim() else null
        activeHistoryMime = "text/plain"
        activeHistoryTextPayload = rawText

        val payloadCandidate = TapPayload(
            t = "text",
            s = settings.effectiveDeviceName(),
            d = rawText
        )
        val candidateJson = payloadCandidate.toJson()
        val encodedNdefBytes = NdefPayloadStore.measureNdefSize(candidateJson)

        if (encodedNdefBytes <= MAX_NFC_NDEF_BYTES) {
            isShortTextMode = true
            lastPayloadSummary = if (isUrl) {
                "リンクを送信: ${rawText.trim()}"
            } else {
                "テキストを送信 (${bytes.size} B)"
            }

            if (isUrl) {
                val domain = runCatching { Uri.parse(rawText.trim()).host }.getOrNull().orEmpty().ifBlank { "URL リンク" }
                stage.setContent(StageContent.Url(domain = domain, fullUrl = rawText.trim()))
            } else {
                stage.setContent(
                    StageContent.Text(
                        title = "テキストを送信",
                        preview = rawText.trim().take(200),
                        charCount = rawText.length
                    )
                )
            }
            stage.setPeerSubtitle("${settings.effectiveDeviceName()} から送信")

            if (!nfcController.isNfcEnabled) {
                showError("NFC がオフです。端末設定で NFC をオンにしてください")
                return
            }

            lastPayloadJson = candidateJson
            nfcController.startBroadcast(candidateJson, lastPayloadSummary)

            HapticFeedbackEngine.playReadyTick(this)
            stage.setPhaseReady(
                pillText = "相手端末の背面をかざしてください",
                detail = "",
                routeTag = "NFC"
            )
            stage.configureActions(closeText = "キャンセル") { cancelAndClose() }
        } else {
            val item = SharedItem(
                name = "Shared-text.txt",
                size = bytes.size.toLong(),
                mime = "text/plain",
                textBytes = bytes
            )
            startFileBroadcast(listOf(item), forceP2p = false, isLargeText = true, labelOverride = "長文テキスト (${UiHelpers.formatBytes(bytes.size.toLong())})")
        }
    }

    private fun prepareAndStartUris(uris: List<Uri>, relDirs: List<String>?) {
        val startBlock = {
            prepareJob?.cancel()
            prepareJob = uiScope.launch {
                stage.setPhasePreparing("ファイル情報を読み込み中...", "選択されたファイルを確認しています")
                stage.configureActions(closeText = "キャンセル") { cancelAndClose() }
                val items = withContext(Dispatchers.IO) {
                    val resolved = mutableListOf<SharedItem>()
                    for ((idx, uri) in uris.withIndex()) {
                        currentCoroutineContext().ensureActive()
                        if (!UiHelpers.canReadUri(applicationContext, uri)) {
                            return@withContext null
                        }
                        val (name, size, mime) = UiHelpers.queryUriMeta(applicationContext, uri)
                        val relDir = relDirs?.getOrNull(idx).orEmpty()
                        resolved.add(SharedItem(name = name, relativeDir = relDir, size = size, mime = mime, uri = uri))
                    }
                    resolved
                }
                if (items == null) {
                    showError("このファイルは読み取り権限がないため共有できません")
                    return@launch
                }
                prepareJob = null
                startFileBroadcast(items, forceP2p = false, isLargeText = false, labelOverride = null)
            }
            Unit
        }

        val missing = UiHelpers.missingWifiPermissions(this)
        if (missing.isEmpty()) {
            startBlock()
        } else {
            pendingFileAction = startBlock
            permissionLauncher.launch(missing)
        }
    }

    private sealed class FolderScanResult {
        data class Success(val files: List<Pair<Uri, String>>) : FolderScanResult()
        data class LimitExceeded(val message: String) : FolderScanResult()
    }

    private fun prepareAndStartFolderTree(treeUri: Uri) {
        val startBlock = {
            prepareJob?.cancel()
            prepareJob = uiScope.launch {
                stage.setPhasePreparing("フォルダ内をスキャン中...", "ファイル一覧を取得しています")
                stage.configureActions(closeText = "キャンセル") { cancelAndClose() }
                val scanResult = try {
                    withContext(Dispatchers.IO) {
                        collectTreeFiles(treeUri)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    showError("フォルダの読み込みに失敗しました: ${e.message}")
                    return@launch
                }

                when (scanResult) {
                    is FolderScanResult.LimitExceeded -> {
                        showError(scanResult.message)
                    }
                    is FolderScanResult.Success -> {
                        if (scanResult.files.isEmpty()) {
                            showError("フォルダ内にファイルが見つかりませんでした")
                        } else {
                            val uris = scanResult.files.map { it.first }
                            val relDirs = scanResult.files.map { it.second }
                            prepareAndStartUris(uris, relDirs)
                        }
                    }
                }
            }
            Unit
        }

        val missing = UiHelpers.missingWifiPermissions(this)
        if (missing.isEmpty()) {
            startBlock()
        } else {
            pendingFileAction = startBlock
            permissionLauncher.launch(missing)
        }
    }

    private suspend fun collectTreeFiles(treeUri: Uri): FolderScanResult {
        val result = mutableListOf<Pair<Uri, String>>()
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootFolderName = rootDocId.substringAfterLast('/').substringAfterLast(':').ifBlank { "Folder" }
        var limitError: String? = null

        suspend fun walk(docId: String, currentRelDir: String, depth: Int) {
            currentCoroutineContext().ensureActive()
            if (limitError != null) return
            if (depth > MAX_FOLDER_DEPTH) {
                limitError = "フォルダの階層が深すぎます（上限 ${MAX_FOLDER_DEPTH} 階層）"
                return
            }
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                ),
                null,
                null,
                null
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (cursor.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    if (limitError != null) return
                    if (idIdx < 0) continue
                    val childId = cursor.getString(idIdx)
                    val childName = if (nameIdx >= 0) cursor.getString(nameIdx) else "item"
                    val childMime = if (mimeIdx >= 0) cursor.getString(mimeIdx) else ""
                    if (childMime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        walk(childId, "$currentRelDir/$childName", depth + 1)
                    } else {
                        if (result.size >= MAX_FOLDER_FILES) {
                            limitError = "フォルダ内のファイル数が上限（${MAX_FOLDER_FILES}件）を超えています"
                            return
                        }
                        val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                        result.add(fileUri to currentRelDir)
                    }
                }
            }
        }

        walk(rootDocId, rootFolderName, 0)
        return limitError?.let { FolderScanResult.LimitExceeded(it) } ?: FolderScanResult.Success(result)
    }

    private fun startFileBroadcast(
        items: List<SharedItem>,
        forceP2p: Boolean,
        isLargeText: Boolean,
        labelOverride: String?
    ) {
        val missing = UiHelpers.missingWifiPermissions(this)
        if (missing.isNotEmpty()) {
            pendingFileAction = { startFileBroadcast(items, forceP2p, isLargeText, labelOverride) }
            permissionLauncher.launch(missing)
            return
        }

        cleanupActiveTransfer()
        transferFinished = false
        isShortTextMode = false

        val totalBytes = items.sumOf { maxOf(0L, it.size) }
        activeTotalBytes = totalBytes
        activeFileCount = items.size
        val isAllImages = items.all { it.mime.startsWith("image/", ignoreCase = true) }
        val isAllVideos = items.all { it.mime.startsWith("video/", ignoreCase = true) }
        activeCategoryLabel = when {
            isLargeText -> "TEXT"
            isAllImages -> "IMAGE"
            isAllVideos -> "VIDEO"
            else -> "FILE"
        }
        activeTitle = labelOverride ?: if (items.size == 1) {
            items.first().name
        } else {
            "${items.first().name} ほか ${items.size - 1} 件"
        }
        activeHistoryUriString = items.firstOrNull()?.uri?.toString()
        activeHistoryMime = items.firstOrNull()?.mime
        if (!isLargeText) activeHistoryTextPayload = null

        val summaryLabel = labelOverride ?: if (items.size == 1) {
            "${items.first().name} (${UiHelpers.formatBytes(totalBytes)})"
        } else {
            "${items.size} ファイル • 合計 ${UiHelpers.formatBytes(totalBytes)}"
        }
        lastPayloadSummary = summaryLabel

        stage.setPeerSubtitle("${settings.effectiveDeviceName()} から送信")
        bindStageContentForItems(items, totalBytes, forceP2p, isLargeText, labelOverride)

        if (!nfcController.isNfcEnabled) {
            showError("NFC がオフです。端末設定で NFC をオンにしてください")
            return
        }

        if (!UiHelpers.isWifiEnabled(this)) {
            pendingWifiRetry = { startFileBroadcast(items, forceP2p, isLargeText, labelOverride) }
            HapticFeedbackEngine.playError(this)
            stage.showError(
                pillText = "Wi-Fi がオフです",
                message = "Wi-Fi をオンにしてください"
            )
            stage.configureActions(
                primary = StageAction("Wi-Fiをオンにする") { UiHelpers.openWifiPanel(this) },
                closeText = "閉じる",
                onClose = { cancelAndClose() }
            )
            return
        }

        if (UiHelpers.isLocationRequiredAndDisabled(this)) {
            pendingWifiRetry = { startFileBroadcast(items, forceP2p, isLargeText, labelOverride) }
            HapticFeedbackEngine.playError(this)
            stage.showError(
                pillText = "位置情報がオフです",
                message = "位置情報（GPS）をオンにしてください（Android 10〜12 で Wi-Fi Direct を利用するために必要です）"
            )
            stage.configureActions(
                primary = StageAction("位置情報設定を開く") { UiHelpers.openLocationSettings(this) },
                closeText = "閉じる",
                onClose = { cancelAndClose() }
            )
            return
        }

        stage.setPhasePreparing("送信を準備しています...", "")
        stage.configureActions(closeText = "キャンセル") { cancelAndClose() }
        TransferForegroundService.start(this, "Tap to Share 送信待機", summaryLabel)

        setupJob = uiScope.launch {
            try {
                val token = UUID.randomUUID().toString().replace("-", "").take(16)
                val keyBytes = ChunkCrypto.generateKeyBytes()
                val keyB64 = ChunkCrypto.encodeKeyBase64Url(keyBytes)

                val server = LocalFileServer.createAndroid(
                    context = applicationContext,
                    items = items,
                    token = token,
                    keyBytes = keyBytes,
                    isLargeText = isLargeText,
                    onProgress = { prog ->
                        runOnUiThread {
                            idleTimeoutJob?.cancel()
                            activeLastSpeedMBps = prog.currentMBps
                            val frac = if (prog.totalBytes > 0L) {
                                prog.transferredBytes.toFloat() / prog.totalBytes.toFloat()
                            } else 0f
                            val speedStr = String.format(Locale.US, "%.1f MB/s", prog.currentMBps)
                            val fLabel = "${prog.fileIndex + 1}/${prog.fileCount}: ${prog.fileName} • ${
                                UiHelpers.formatBytes(prog.transferredBytes)
                            } / ${UiHelpers.formatBytes(prog.totalBytes)}"
                            stage.updateProgress(frac, speedStr, fLabel, "AES-GCM")
                            TransferForegroundService.updateProgress(
                                this@ShareSheetActivity,
                                "Tap to Share 送信中",
                                "${prog.fileName} • $speedStr",
                                progress = (frac * 1000f).toInt().coerceIn(0, 1000),
                                max = 1000
                            )
                        }
                    },
                    onClientDone = { remoteIp ->
                        runOnUiThread {
                            activeLastRoute = if (
                                NetworkAddressValidator.sameIpv4Subnet24(remoteIp, activeP2pGoIp) ||
                                remoteIp.startsWith("192.168.49.")
                            ) "P2P" else "LAN"
                            onFileTransferDone()
                        }
                    }
                )

                activeServer = server
                val port = server.start(uiScope)

                val wifiSnap = WifiStatusChecker.capture(this@ShareSheetActivity)
                val p2pGroup = p2pController.createDirectGroup()
                activeP2pGoIp = p2pGroup.goIp

                val useLan = settings.preferLanFirst && !forceP2p && !wifiSnap.wlanIpv4.isNullOrBlank()
                val payload = TapPayload(
                    v = 2,
                    t = "file",
                    s = settings.effectiveDeviceName(),
                    cnt = items.size,
                    total = totalBytes,
                    preview = items.first().name,
                    isLargeText = isLargeText,
                    tok = token,
                    key = keyB64,
                    lanIp = if (useLan) wifiSnap.wlanIpv4 else null,
                    port = port,
                    p2pSsid = p2pGroup.ssid,
                    p2pPass = p2pGroup.passphrase,
                    p2pIp = p2pGroup.goIp,
                    p2pFreq = p2pGroup.frequency,
                    forceP2p = forceP2p
                )

                val json = payload.toJson()
                lastPayloadJson = json
                nfcController.startBroadcast(json, summaryLabel)

                HapticFeedbackEngine.playReadyTick(this@ShareSheetActivity)
                val routeTag = if (useLan) "LAN / P2P" else "P2P"
                stage.setPhaseReady("相手端末の背面をかざしてください", "", routeTag)
                stage.configureActions(closeText = "キャンセル") { cancelAndClose() }

                startIdleTimeout()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Share setup failed", e)
                showError("送信準備エラー: ${e.message}")
            }
        }
    }

    private fun bindStageContentForItems(
        items: List<SharedItem>,
        totalBytes: Long,
        forceP2p: Boolean,
        isLargeText: Boolean,
        labelOverride: String?
    ) {
        thumbnailJob?.cancel()
        val first = items.first()
        val isMedia = !isLargeText && !forceP2p &&
            (first.mime.startsWith("image/", ignoreCase = true) || first.mime.startsWith("video/", ignoreCase = true))

        if (isMedia && first.uri != null) {
            val isVid = first.mime.startsWith("video/", ignoreCase = true)
            stage.setContent(
                StageContent.Media(
                    title = if (items.size == 1) first.name else "${first.name} ほか ${items.size - 1} 件",
                    subtitle = UiHelpers.formatBytes(totalBytes),
                    bitmap = null,
                    isVideo = isVid,
                    count = items.size
                )
            )
            thumbnailJob = uiScope.launch {
                val bmp = ThumbnailLoader.loadThumbnail(this@ShareSheetActivity, first.uri, first.mime, 640)
                if (bmp != null) {
                    stage.updateThumbnail(bmp)
                }
            }
        } else if (isLargeText) {
            val txt = first.textBytes?.toString(Charsets.UTF_8).orEmpty()
            stage.setContent(
                StageContent.Text(
                    title = labelOverride ?: "長文テキスト",
                    preview = txt.trim().take(200),
                    charCount = txt.length
                )
            )
        } else {
            val isFolder = items.any { it.relativeDir.isNotEmpty() }
            stage.setContent(
                StageContent.FileBundle(
                    title = labelOverride ?: activeTitle,
                    subtitle = if (forceP2p) "20MB P2P 5GHz スループット計測" else "合計 ${UiHelpers.formatBytes(totalBytes)}",
                    fileCount = items.size,
                    isFolder = isFolder,
                    isBenchmark = forceP2p
                )
            )
        }
    }

    private fun startIdleTimeout() {
        idleTimeoutJob?.cancel()
        idleTimeoutJob = uiScope.launch {
            delay(IDLE_TIMEOUT_MS)
            if (!transferFinished) {
                nfcController.stopBroadcast()
                cleanupActiveTransfer()
                stage.showError("待機タイムアウト（90秒）", "接続がなかったため P2P 待機を自動終了しました")
                stage.configureActions(closeText = "閉じる") { cancelAndClose() }
            }
        }
    }

    private fun onShortTextSent() {
        transferFinished = true
        historyStore.add(
            HistoryEntry(
                direction = "SEND",
                peerModel = "NFC Receiver",
                category = activeCategoryLabel,
                title = activeTitle,
                summary = lastPayloadSummary,
                totalBytes = activeTotalBytes,
                route = "NFC",
                uriString = activeHistoryUriString,
                mimeType = activeHistoryMime,
                fileCount = 1,
                textPayload = activeHistoryTextPayload
            )
        )
        HapticFeedbackEngine.playTransferComplete(this)
        SoundEffectEngine.playCompleteBloom(this)
        stage.onInstantSendComplete(
            pillText = "送信完了",
            detail = "",
            routeTag = "NFC",
            speedSubtitle = "NFC • ${UiHelpers.formatBytes(activeTotalBytes)}"
        )
        stage.configureActions(
            secondary = StageAction("もう一度") { rearmNfc() },
            closeText = "閉じる",
            onClose = { cancelAndClose() }
        )

        if (settings.autoCloseAfterSend) {
            autoCloseHandler.postDelayed({ if (!isFinishing) finishAnimated() }, 2800L)
        }
    }

    private fun onFileTransferDone() {
        if (transferFinished) return
        transferFinished = true
        idleTimeoutJob?.cancel()
        nfcController.stopBroadcast()
        activeServer?.stop()
        activeServer = null
        TransferForegroundService.stop(this)
        uiScope.launch { p2pController.cleanupGroup() }

        historyStore.add(
            HistoryEntry(
                direction = "SEND",
                peerModel = "Receiver",
                category = activeCategoryLabel,
                title = activeTitle,
                summary = lastPayloadSummary,
                totalBytes = activeTotalBytes,
                route = activeLastRoute,
                speedMBps = activeLastSpeedMBps,
                uriString = activeHistoryUriString,
                mimeType = activeHistoryMime,
                fileCount = activeFileCount,
                textPayload = activeHistoryTextPayload
            )
        )

        HapticFeedbackEngine.playTransferComplete(this)
        SoundEffectEngine.playCompleteBloom(this)

        val detail = ""
        val speedSub = if (activeLastSpeedMBps > 0.0) {
            "${String.format(Locale.US, "%.1f", activeLastSpeedMBps)} MB/s • ${UiHelpers.formatBytes(activeTotalBytes)}"
        } else {
            UiHelpers.formatBytes(activeTotalBytes)
        }
        stage.onComplete(
            pillText = "送信完了",
            detail = detail,
            routeTag = activeLastRoute,
            speedSubtitle = speedSub
        )
        stage.configureActions(closeText = "閉じる") { cancelAndClose() }

        if (settings.autoCloseAfterSend) {
            autoCloseHandler.postDelayed({ if (!isFinishing) finishAnimated() }, 2800L)
        }
    }

    private fun rearmNfc() {
        val json = lastPayloadJson ?: return
        autoCloseHandler.removeCallbacksAndMessages(null)
        transferFinished = false
        nfcController.startBroadcast(json, lastPayloadSummary)
        HapticFeedbackEngine.playReadyTick(this)
        stage.setPhaseReady("相手端末の背面をかざしてください", "", "NFC")
        stage.configureActions(closeText = "キャンセル") { cancelAndClose() }
        if (!isShortTextMode) {
            startIdleTimeout()
        }
    }

    private fun showError(message: String) {
        cleanupActiveTransfer()
        HapticFeedbackEngine.playError(this)
        SoundEffectEngine.playErrorTone(this)
        stage.showError("エラー", message)
        stage.configureActions(closeText = "閉じる") { cancelAndClose() }
    }

    private fun cleanupActiveTransfer() {
        pendingWifiRetry = null
        prepareJob?.cancel()
        setupJob?.cancel()
        idleTimeoutJob?.cancel()
        activeServer?.stop()
        activeServer = null
        TransferForegroundService.stop(this)
        uiScope.launch { p2pController.cleanupGroup() }
    }

    private fun cancelAndClose() {
        autoCloseHandler.removeCallbacksAndMessages(null)
        demoJob?.cancel()
        nfcController.stopBroadcast()
        cleanupActiveTransfer()
        finishAnimated()
    }

    private fun finishAnimated() {
        if (isClosing || isFinishing) return
        isClosing = true
        stage.playExit {
            disableWindowTransitions()
            finishAndRemoveTask()
        }
    }

    private fun getParcelableExtraCompat(intent: Intent, key: String): Uri? {
        @Suppress("DEPRECATION")
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(key, Uri::class.java)
        } else {
            intent.getParcelableExtra(key)
        }
    }

    private fun getParcelableListExtra(intent: Intent, key: String): ArrayList<Uri>? {
        @Suppress("DEPRECATION")
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableArrayListExtra(key, Uri::class.java)
        } else {
            intent.getParcelableArrayListExtra(key)
        }
    }
}
