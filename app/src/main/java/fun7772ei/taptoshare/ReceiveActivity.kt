package fun7772ei.taptoshare

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import fun7772ei.taptoshare.ui.HapticFeedbackEngine
import fun7772ei.taptoshare.ui.SoundEffectEngine
import fun7772ei.taptoshare.ui.ThumbnailLoader
import fun7772ei.taptoshare.ui.animation.StageAction
import fun7772ei.taptoshare.ui.animation.StageContent
import fun7772ei.taptoshare.ui.animation.TransferStageView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.OutputStream
import java.util.Locale

class ReceiveActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "TapToShare"
        private const val STATE_INTENT_HANDLED = "state_intent_handled"
        private const val MAX_DIALOG_PREVIEW_CHARS = 32_768
        const val EXTRA_DEMO_MODE = "demo_mode"
    }

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val autoCloseHandler = Handler(Looper.getMainLooper())

    private lateinit var settings: AppSettings
    private lateinit var historyStore: TransferHistoryStore
    private lateinit var p2pController: P2pController
    private lateinit var stage: TransferStageView
    private var nfcAdapter: NfcAdapter? = null
    private var nfcMuted = false
    private var foregroundDispatchActive = false

    private var intentHandled = false
    private var isClosing = false
    private var receiveJob: Job? = null
    private var demoJob: Job? = null
    private var activeFileToken: String? = null
    private var isDemoMode = false
    private var permissionDeferred: CompletableDeferred<Boolean>? = null

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
        val granted = UiHelpers.missingWifiPermissions(this).isEmpty()
        permissionDeferred?.complete(granted)
        permissionDeferred = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = AppSettings(this)
        historyStore = TransferHistoryStore(this)
        p2pController = P2pController(this)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        UiHelpers.configureEdgeToEdgeBars(this, darkBackground = true)
        UiHelpers.applyWindowBlur(window, true, 16)

        stage = TransferStageView(
            context = this,
            isReceiver = true,
            onBlurRadiusChanged = { radius ->
                UiHelpers.applyWindowBlur(window, radius > 0, radius)
            },
            onBackdropTap = {
                if (receiveJob?.isActive != true && demoJob?.isActive != true) {
                    finishAnimated()
                }
            }
        )
        setContentView(stage)
        stage.playEntrance()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!stage.dismissLightbox()) {
                    cancelAndClose()
                }
            }
        })

        ContextCompat.registerReceiver(
            this,
            cancelReceiver,
            IntentFilter(TransferForegroundService.ACTION_CANCEL_BROADCAST),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        intentHandled = savedInstanceState?.getBoolean(STATE_INTENT_HANDLED, false) ?: false
        if (!intentHandled) {
            intentHandled = true
            processIntent(intent)
        } else {
            finish()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_INTENT_HANDLED, intentHandled)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        if (isDemoMode) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            runCatching {
                nfcAdapter?.setDiscoveryTechnology(
                    this,
                    NfcAdapter.FLAG_READER_DISABLE,
                    NfcAdapter.FLAG_LISTEN_DISABLE
                )
                nfcMuted = true
            }
        } else {
            runCatching {
                val adapter = nfcAdapter ?: return@runCatching
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
                val pi = PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    flags
                )
                adapter.enableForegroundDispatch(this, pi, null, null)
                foregroundDispatchActive = true
            }
        }
    }

    override fun onPause() {
        if (nfcMuted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            nfcMuted = false
            runCatching { nfcAdapter?.resetDiscoveryTechnology(this) }
        } else if (foregroundDispatchActive) {
            foregroundDispatchActive = false
            runCatching { nfcAdapter?.disableForegroundDispatch(this) }
        }
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intentHandled = true
        processIntent(intent)
    }

    override fun onDestroy() {
        autoCloseHandler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(cancelReceiver) }
        demoJob?.cancel()
        receiveJob?.cancel()
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

    private fun processIntent(intent: Intent?) {
        if (intent == null) return
        val isInternalLaunch = InternalLaunchToken.consume(intent)
        if (isInternalLaunch && intent.getBooleanExtra(EXTRA_DEMO_MODE, false)) {
            isDemoMode = true
            runReceiveDemo()
            return
        }
        isDemoMode = false

        if (intent.action != NfcAdapter.ACTION_NDEF_DISCOVERED || !hasNfcTag(intent)) {
            showError("不正な起動リクエストです（NFC タグ情報が見つかりません）")
            return
        }

        val messages = extractNdefMessages(intent)
        if (messages.isEmpty()) {
            showError("NDEFデータが見つかりません")
            return
        }

        val mimeRecord = messages
            .flatMap { it.records.toList() }
            .firstOrNull { rec ->
                rec.tnf == NdefRecord.TNF_MIME_MEDIA &&
                    String(rec.type, Charsets.US_ASCII) == NdefPayloadStore.MIME_TYPE
            }

        if (mimeRecord == null) {
            showError("Tap to Share 以外の NFC タグです")
            return
        }

        val json = String(mimeRecord.payload, Charsets.UTF_8)
        val payload = runCatching { TapPayload.fromJson(json) }.getOrElse { e ->
            showError("データ解析エラー: ${e.message}")
            return
        }

        val wifiBefore = WifiStatusChecker.capture(this)
        Log.d(TAG, "Received NDEF type=${payload.t}")

        HapticFeedbackEngine.playNfcContact(this)
        SoundEffectEngine.playContactShimmer(this)
        stage.setPeerSubtitle("${payload.s} から受信")

        if (payload.t == "text") {
            handleTextPayload(payload, payload.d.orEmpty(), route = "NFC")
        } else if (payload.t == "file") {
            handleFilePayload(payload, wifiBefore)
        } else {
            showError("未対応の種別です: ${payload.t}")
        }
    }

    private fun runReceiveDemo() {
        demoJob?.cancel()
        receiveJob?.cancel()
        stage.dismissLightbox()

        val demoBitmap = ThumbnailLoader.createDemoArtworkBitmap()

        stage.setPeerSubtitle(settings.effectiveDeviceName())
        stage.setContent(
            StageContent.Media(
                title = "Aurora_Twilight_01.jpg",
                subtitle = "3.84 MB",
                bitmap = demoBitmap
            )
        )
        stage.onTransferContact(
            pillText = "受信準備中...",
            detail = "",
            routeTag = "NFC"
        )
        stage.configureActions(closeText = "閉じる") { cancelAndClose() }
        HapticFeedbackEngine.playNfcContact(this)
        SoundEffectEngine.playContactShimmer(this)

        demoJob = uiScope.launch {
            delay(560L)
            val steps = 25
            val demoTotalBytes = 4_026_532L
            for (i in 1..steps) {
                val frac = i.toFloat() / steps
                val speed = 52.0 + (i % 5) * 2.6
                val bytesRecv = (demoTotalBytes * frac).toLong()
                stage.updateProgress(
                    fraction = frac,
                    speedText = String.format(Locale.US, "%.1f MB/s", speed),
                    fileLabel = "1/1: Aurora_Twilight_01.jpg • ${UiHelpers.formatBytes(bytesRecv)} / 3.84 MB",
                    routeTag = "P2P 5GHz"
                )
                delay(52L)
            }

            HapticFeedbackEngine.playTransferComplete(this@ReceiveActivity)
            SoundEffectEngine.playCompleteBloom(this@ReceiveActivity)
            stage.onComplete(
                pillText = "受信完了",
                detail = "3.84 MB • 62.8 MB/s",
                routeTag = "P2P 5GHz"
            )
            stage.onCardTap = {
                stage.showImageLightbox(demoBitmap, "Aurora_Twilight_01.jpg")
            }
            stage.configureActions(
                primary = StageAction("拡大表示") {
                    stage.showImageLightbox(demoBitmap, "Aurora_Twilight_01.jpg")
                },
                secondary = StageAction("もう一度") { runReceiveDemo() },
                closeText = "閉じる",
                onClose = { cancelAndClose() }
            )
        }
    }

    private fun handleTextPayload(payload: TapPayload, rawText: String, route: String) {
        receiveJob?.cancel()
        activeFileToken = null

        val trimmed = rawText.trim()
        if (ReceiveActionHandler.isSingleUrl(trimmed)) {
            val (copied, opened) = ReceiveActionHandler.applyUrlActions(this, settings, trimmed)
            historyStore.add(
                HistoryEntry(
                    direction = "RECEIVE",
                    peerModel = payload.s,
                    category = "URL",
                    title = trimmed,
                    summary = if (copied) "リンク（クリップボードへコピー済み）" else "リンク",
                    totalBytes = trimmed.toByteArray(Charsets.UTF_8).size.toLong(),
                    route = route,
                    uriString = trimmed,
                    fileCount = 1,
                    textPayload = trimmed
                )
            )
            HapticFeedbackEngine.playTransferComplete(this)
            SoundEffectEngine.playCompleteBloom(this)

            val domain = runCatching { Uri.parse(trimmed).host }.getOrNull().orEmpty().ifBlank { "Web リンク" }
            stage.setContent(StageContent.Url(domain = domain, fullUrl = trimmed))
            stage.onCardTap = { ReceiveActionHandler.openUrl(this, settings, trimmed) }
            stage.onComplete(
                pillText = if (opened) "ブラウザで開きました" else "受信完了",
                detail = trimmed,
                routeTag = route
            )
            stage.configureActions(
                primary = StageAction("開く") { ReceiveActionHandler.openUrl(this, settings, trimmed) },
                secondary = StageAction("コピー") {
                    val ok = ReceiveActionHandler.copyTextToClipboard(this, "Tap to Share URL", trimmed)
                    val msg = if (ok) "リンクをコピーしました" else "サイズが大きすぎるためコピーできません"
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                },
                closeText = "閉じる",
                onClose = { cancelAndClose() }
            )

            Log.d(TAG, "NFC URL received opened=$opened")
            if (opened) {
                autoCloseHandler.postDelayed({ if (!isFinishing) finishAnimated() }, 650L)
            }
        } else {
            receiveJob = uiScope.launch {
                val saved = try {
                    withContext(Dispatchers.IO) {
                        MediaSaver.saveTextFile(applicationContext, settings, rawText)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to save received text", e)
                    showError("テキストの保存に失敗しました: ${e.message}")
                    return@launch
                }

                val copied = ReceiveActionHandler.applyTextActions(this@ReceiveActivity, settings, rawText)
                val title = trimmed.lineSequence().firstOrNull().orEmpty().take(48).ifEmpty { saved.name }
                historyStore.add(
                    HistoryEntry(
                        direction = "RECEIVE",
                        peerModel = payload.s,
                        category = "TEXT",
                        title = title,
                        summary = "保存: ${saved.displayPath}",
                        totalBytes = saved.size,
                        route = route,
                        uriString = saved.uri.toString(),
                        mimeType = "text/plain",
                        fileCount = 1,
                        textPayload = rawText
                    )
                )
                HapticFeedbackEngine.playTransferComplete(this@ReceiveActivity)
                SoundEffectEngine.playCompleteBloom(this@ReceiveActivity)

                stage.setContent(
                    StageContent.Text(
                        title = title,
                        preview = trimmed.take(240),
                        charCount = rawText.length
                    )
                )
                val dialogPreview = if (rawText.length > MAX_DIALOG_PREVIEW_CHARS) {
                    rawText.take(MAX_DIALOG_PREVIEW_CHARS) + "\n\n...（長文のため省略しました。保存ファイルから全文を確認できます）"
                } else {
                    rawText
                }
                stage.onCardTap = {
                    MaterialAlertDialogBuilder(this@ReceiveActivity)
                        .setTitle("受信したテキスト")
                        .setMessage(dialogPreview)
                        .setPositiveButton("コピー") { _, _ ->
                            val ok = ReceiveActionHandler.copyTextToClipboard(
                                this@ReceiveActivity,
                                "Tap to Share Text",
                                rawText
                            )
                            val toastMsg = if (ok) {
                                "テキストをコピーしました"
                            } else {
                                "サイズが大きすぎるためクリップボードへコピーできません（ファイル保存済み）"
                            }
                            Toast.makeText(this@ReceiveActivity, toastMsg, Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton("閉じる", null)
                        .show()
                }
                stage.onComplete(
                    pillText = if (copied) "保存 & コピー完了" else "受信完了",
                    detail = saved.displayPath,
                    routeTag = route
                )
                stage.configureActions(
                    primary = StageAction("コピー") {
                        val ok = ReceiveActionHandler.copyTextToClipboard(
                            this@ReceiveActivity,
                            "Tap to Share Text",
                            rawText
                        )
                        val toastMsg = if (ok) {
                            "テキストをコピーしました"
                        } else {
                            "サイズが大きすぎるためクリップボードへコピーできません（ファイル保存済み）"
                        }
                        Toast.makeText(this@ReceiveActivity, toastMsg, Toast.LENGTH_SHORT).show()
                    },
                    secondary = StageAction("共有") { shareTextExternal(rawText) },
                    closeText = "閉じる",
                    onClose = { cancelAndClose() }
                )
                Log.d(TAG, "NFC text received")
            }
        }
    }

    private fun handleFilePayload(payload: TapPayload, wifiBefore: WifiSnapshot) {
        if (!NetworkAddressValidator.isValidPort(payload.port)) {
            showError("不正なポート番号が指定されました")
            return
        }
        if (!payload.lanIp.isNullOrBlank() && !NetworkAddressValidator.isPrivateIpv4(payload.lanIp)) {
            showError("不正な送信元 IP アドレス（LAN）が指定されました")
            return
        }
        if (!payload.p2pIp.isNullOrBlank() && !NetworkAddressValidator.isPrivateIpv4(payload.p2pIp)) {
            showError("不正な送信元 IP アドレス（P2P）が指定されました")
            return
        }

        val token = payload.tok ?: ""
        if (receiveJob?.isActive == true && !activeFileToken.isNullOrEmpty() && activeFileToken == token) {
            Log.d(TAG, "Duplicate NFC tap ignored")
            return
        }

        receiveJob?.cancel()
        activeFileToken = token.ifEmpty { null }
        stage.configureActions(closeText = "キャンセル") { cancelAndClose() }

        val previewName = payload.preview ?: "shared_file.bin"
        val isBenchmark = payload.cnt == 1 && previewName == LocalFileServer.BENCHMARK_NAME
        val displaySummary = if (payload.cnt > 1) {
            "${payload.cnt} ファイル • 合計 ${UiHelpers.formatBytes(payload.total)}"
        } else {
            "$previewName (${UiHelpers.formatBytes(payload.total)})"
        }

        stage.setContent(
            StageContent.FileBundle(
                title = if (isBenchmark) "20MB P2P診断" else previewName,
                subtitle = displaySummary,
                fileCount = payload.cnt,
                isBenchmark = isBenchmark
            )
        )
        stage.onTransferContact(
            pillText = "接続準備中...",
            detail = displaySummary,
            routeTag = "NFC"
        )

        TransferForegroundService.start(this, "Tap to Share 受信中", displaySummary)

        receiveJob = uiScope.launch {
            var p2pUsed = false
            try {
                val keyBytes = payload.key?.let { ChunkCrypto.decodeKeyBase64Url(it) }
                    ?: error("暗号鍵 (key) がペイロードに含まれていません")

                var result: BatchTransferResult<SavedFile>? = null
                var wifiDuring = wifiBefore

                val saveHandler: suspend (ManifestItem, suspend (OutputStream) -> Long) -> Pair<Long, SavedFile?> =
                    { item, streamWriter ->
                        if (isBenchmark && item.name == LocalFileServer.BENCHMARK_NAME) {
                            val nullOut = object : OutputStream() {
                                override fun write(b: Int) = Unit
                                override fun write(b: ByteArray, off: Int, len: Int) = Unit
                            }
                            val written = streamWriter(nullOut)
                            written to null
                        } else {
                            val saved = MediaSaver.saveStream(
                                context = applicationContext,
                                settings = settings,
                                name = item.name,
                                relativeDir = item.relativeDir,
                                mime = item.mime,
                                writer = streamWriter
                            )
                            saved.size to saved
                        }
                    }

                val progressHandler: (TransferProgress) -> Unit = { prog ->
                    runOnUiThread {
                        val frac = if (prog.totalBytes > 0L) {
                            prog.transferredBytes.toFloat() / prog.totalBytes.toFloat()
                        } else 0f
                        val speedStr = String.format(Locale.US, "%.1f MB/s", prog.currentMBps)
                        val label = "${prog.fileIndex + 1}/${prog.fileCount}: ${prog.fileName} • ${
                            UiHelpers.formatBytes(prog.transferredBytes)
                        } / ${UiHelpers.formatBytes(prog.totalBytes)}"
                        stage.updateProgress(frac, speedStr, label, "AES-GCM")
                        TransferForegroundService.updateProgress(
                            this@ReceiveActivity,
                            "Tap to Share 受信中",
                            "${prog.fileName} • $speedStr",
                            progress = (frac * 1000f).toInt().coerceIn(0, 1000),
                            max = 1000
                        )
                    }
                }

                if (settings.preferLanFirst && !payload.forceP2p && !payload.lanIp.isNullOrBlank() && payload.port > 0) {
                    stage.onTransferContact("同一LAN接続を試行中...", displaySummary, "LAN")
                    Log.d(TAG, "Trying LAN transfer")
                    val lanResult = withContext(Dispatchers.IO) {
                        try {
                            LocalFileClient.downloadBatch(
                                host = payload.lanIp,
                                port = payload.port,
                                token = token,
                                keyBytes = keyBytes,
                                bindLocalIp = null,
                                routeName = "LAN",
                                connectTimeoutMs = 1200,
                                onProgress = progressHandler,
                                saveHandler = saveHandler
                            )
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.d(TAG, "LAN transfer failed, falling back to P2P: ${e.message}")
                            null
                        }
                    }
                    if (lanResult != null) {
                        result = lanResult
                        wifiDuring = WifiStatusChecker.capture(this@ReceiveActivity)
                    }
                }

                if (result == null) {
                    val ssid = payload.p2pSsid ?: error("Missing p2pSsid")
                    val pass = payload.p2pPass ?: error("Missing p2pPass")

                    if (!UiHelpers.isWifiEnabled(this@ReceiveActivity)) {
                        showError(
                            msg = "Wi-Fi スイッチがオフです。ファイル受信には Wi-Fi をオンにしてください（ルーター未接続でも動作します）",
                            primaryAction = StageAction("Wi-Fiをオンにする") { UiHelpers.openWifiPanel(this@ReceiveActivity) }
                        )
                        return@launch
                    }

                    if (!ensureWifiPermissionSuspend()) {
                        error("Wi-Fi Direct 権限が許可されていません")
                    }

                    if (UiHelpers.isLocationRequiredAndDisabled(this@ReceiveActivity)) {
                        showError(
                            msg = "位置情報（GPS）をオンにしてください（Android 10〜12 で Wi-Fi Direct を利用するために必要です）",
                            primaryAction = StageAction("位置情報設定を開く") {
                                UiHelpers.openLocationSettings(this@ReceiveActivity)
                            }
                        )
                        return@launch
                    }

                    stage.onTransferContact("接続中...", displaySummary, "P2P")
                    Log.d(TAG, "Connecting P2P freq=${payload.p2pFreq}")

                    p2pUsed = true
                    val conn = p2pController.connectToDirectGroup(ssid, pass, payload.p2pFreq)
                    wifiDuring = WifiStatusChecker.capture(this@ReceiveActivity)
                    Log.d(TAG, "P2P connected in ${conn.connectMs}ms")

                    val targetIp = payload.p2pIp?.takeIf { it.isNotBlank() } ?: conn.goIp
                    if (!NetworkAddressValidator.isPrivateIpv4(targetIp)) {
                        error("不正な接続先 IP アドレスが指定されました")
                    }
                    stage.onTransferContact("受信中...", displaySummary, "P2P")

                    result = withContext(Dispatchers.IO) {
                        LocalFileClient.downloadBatch(
                            host = targetIp,
                            port = payload.port,
                            token = token,
                            keyBytes = keyBytes,
                            bindLocalIp = conn.localP2pIpv4,
                            routeName = "P2P",
                            connectTimeoutMs = 8000,
                            onProgress = progressHandler,
                            saveHandler = saveHandler
                        )
                    }
                    wifiDuring = WifiStatusChecker.capture(this@ReceiveActivity)
                    p2pController.cleanupGroup()
                    delay(400)
                }

                TransferForegroundService.stop(this@ReceiveActivity)
                val wifiAfter = WifiStatusChecker.capture(this@ReceiveActivity)
                val wifiDropped = wifiBefore.hasWifiTransport && (
                    !wifiDuring.hasWifiTransport ||
                        !wifiAfter.hasWifiTransport ||
                        (wifiBefore.hasInternet && (!wifiDuring.hasInternet || !wifiAfter.hasInternet))
                    )

                if (result.manifest.isLargeText && result.largeTextContent != null) {
                    handleTextPayload(payload, result.largeTextContent, route = result.route)
                    return@launch
                }

                if (isBenchmark) {
                    showBenchmarkSuccess(result, wifiDropped, wifiBefore, wifiDuring, wifiAfter)
                    return@launch
                }

                val savedFiles = result.entries.mapNotNull { it.saved }
                val category = ReceiveActionHandler.classifyFiles(savedFiles)
                val (copied, autoOpened) = ReceiveActionHandler.applyFileActions(
                    this@ReceiveActivity,
                    settings,
                    savedFiles,
                    category
                )

                val firstSaved = savedFiles.firstOrNull()
                val title = if (savedFiles.size == 1) {
                    firstSaved?.name ?: previewName
                } else {
                    "${firstSaved?.name ?: previewName} ほか ${savedFiles.size - 1} 件"
                }

                var thumbBitmap: Bitmap? = null
                if ((category == ReceiveCategory.IMAGE || category == ReceiveCategory.VIDEO) && firstSaved != null) {
                    thumbBitmap = withTimeoutOrNull(450L) {
                        ThumbnailLoader.loadThumbnail(this@ReceiveActivity, firstSaved.uri, firstSaved.mime, 720)
                    }
                    stage.setContent(
                        StageContent.Media(
                            title = title,
                            subtitle = UiHelpers.formatBytes(result.totalBytes),
                            bitmap = thumbBitmap,
                            isVideo = category == ReceiveCategory.VIDEO,
                            count = savedFiles.size
                        )
                    )
                    if (thumbBitmap == null) {
                        uiScope.launch {
                            val lateBmp = ThumbnailLoader.loadThumbnail(this@ReceiveActivity, firstSaved.uri, firstSaved.mime, 720)
                            if (lateBmp != null) {
                                stage.updateThumbnail(lateBmp)
                                if (category == ReceiveCategory.IMAGE) {
                                    stage.onCardTap = { stage.showImageLightbox(lateBmp, title) }
                                }
                            }
                        }
                    }
                } else {
                    stage.setContent(
                        StageContent.FileBundle(
                            title = title,
                            subtitle = "${UiHelpers.formatBytes(result.totalBytes)} • ${firstSaved?.displayPath ?: settings.getDestinationDisplayName()}",
                            fileCount = savedFiles.size,
                            isFolder = savedFiles.any { it.relativeDir.isNotEmpty() }
                        )
                    )
                }

                historyStore.add(
                    HistoryEntry(
                        direction = "RECEIVE",
                        peerModel = payload.s,
                        category = when (category) {
                            ReceiveCategory.IMAGE -> "IMAGE"
                            ReceiveCategory.VIDEO -> "VIDEO"
                            else -> "FILE"
                        },
                        title = title,
                        summary = firstSaved?.displayPath ?: settings.getDestinationDisplayName(),
                        totalBytes = result.totalBytes,
                        route = result.route,
                        speedMBps = result.mBps,
                        uriString = firstSaved?.uri?.toString(),
                        mimeType = firstSaved?.mime,
                        fileCount = savedFiles.size
                    )
                )

                HapticFeedbackEngine.playTransferComplete(this@ReceiveActivity)
                SoundEffectEngine.playCompleteBloom(this@ReceiveActivity)

                val speedLabel = String.format(Locale.US, "%.1f MB/s", result.mBps)
                val detail = firstSaved?.displayPath ?: settings.getDestinationDisplayName()

                stage.onComplete(
                    pillText = "受信完了",
                    detail = detail,
                    routeTag = "${result.route} • $speedLabel"
                )
                stage.setTelemetryText(speedLabel)

                if (category == ReceiveCategory.IMAGE && thumbBitmap != null) {
                    val bmp = thumbBitmap
                    stage.onCardTap = { stage.showImageLightbox(bmp, title) }
                } else if (savedFiles.size > 1) {
                    stage.onCardTap = { showReceivedFileListDialog(savedFiles) }
                }

                val primaryAction = if (savedFiles.isNotEmpty()) {
                    val label = if (category == ReceiveCategory.OTHER && savedFiles.size > 1) "ダウンロード一覧" else "開く"
                    StageAction(label) {
                        val ok = ReceiveActionHandler.openSavedFiles(this@ReceiveActivity, settings, savedFiles, category)
                        if (!ok) {
                            Toast.makeText(this@ReceiveActivity, "対応するアプリが見つかりません", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else null

                val secondaryAction = when {
                    category == ReceiveCategory.IMAGE && firstSaved != null -> StageAction("URIコピー") {
                        ReceiveActionHandler.copyUriToClipboard(this@ReceiveActivity, "Tap to Share Image", firstSaved.uri)
                        Toast.makeText(this@ReceiveActivity, "画像URIをコピーしました", Toast.LENGTH_SHORT).show()
                    }
                    firstSaved != null -> StageAction("共有") {
                        shareSavedFilesExternal(savedFiles)
                    }
                    else -> null
                }

                stage.configureActions(
                    primary = primaryAction,
                    secondary = secondaryAction,
                    closeText = "閉じる",
                    onClose = { cancelAndClose() }
                )
            } catch (e: CancellationException) {
                if (p2pUsed) {
                    withContext(kotlinx.coroutines.NonCancellable) {
                        runCatching { p2pController.cleanupGroup() }
                    }
                }
                throw e
            } catch (e: Exception) {
                TransferForegroundService.stop(this@ReceiveActivity)
                if (p2pUsed) {
                    p2pController.cleanupGroup()
                }
                Log.e(TAG, "Receive file failed", e)
                showError("受信エラー: ${e.message}")
            } finally {
                if (activeFileToken == token) {
                    activeFileToken = null
                }
            }
        }
    }

    private fun showBenchmarkSuccess(
        result: BatchTransferResult<SavedFile>,
        wifiDropped: Boolean,
        wifiBefore: WifiSnapshot,
        wifiDuring: WifiSnapshot,
        wifiAfter: WifiSnapshot
    ) {
        HapticFeedbackEngine.playTransferComplete(this)
        SoundEffectEngine.playCompleteBloom(this)
        val speedLine = "${String.format(Locale.US, "%.2f", result.mBps)} MB/s (${
            String.format(Locale.US, "%.1f", result.mbps)
        } Mbps)"
        stage.setContent(
            StageContent.FileBundle(
                title = "20MB P2P診断 完了",
                subtitle = speedLine,
                fileCount = 1,
                isBenchmark = true
            )
        )
        val detail = buildString {
            append("接続時間: ").append(result.connectMs).append(" ms • 転送時間: ").append(result.transferMs).append(" ms\n")
            append("受信サイズ: ").append(result.totalBytes).append(" bytes • 既存Wi-Fi維持: ")
            append(if (!wifiDropped) "OK (切断なし)" else "DROPPED")
        }
        stage.onComplete(
            pillText = "診断完了",
            detail = detail,
            routeTag = "${result.route} • $speedLine"
        )
        stage.configureActions(closeText = "閉じる") { cancelAndClose() }
        Log.d(TAG, "Benchmark complete route=${result.route} transferMs=${result.transferMs} wifiDropped=$wifiDropped")
    }

    private fun showReceivedFileListDialog(files: List<SavedFile>) {
        val labels = files.map { f ->
            val path = if (f.relativeDir.isNotEmpty()) "${f.relativeDir}/${f.name}" else f.name
            "$path (${UiHelpers.formatBytes(f.size)})"
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("受信ファイル一覧 (${files.size}件)")
            .setItems(labels) { _, which ->
                val chosen = files[which]
                val cat = ReceiveActionHandler.classifyFiles(listOf(chosen))
                ReceiveActionHandler.openSavedFiles(this, settings, listOf(chosen), cat)
            }
            .setNegativeButton("閉じる", null)
            .show()
    }

    private fun shareTextExternal(text: String) {
        val intent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text)
        runCatching { startActivity(Intent.createChooser(intent, "テキストを共有")) }
    }

    private fun shareSavedFilesExternal(files: List<SavedFile>) {
        if (files.isEmpty()) return
        val intent = if (files.size == 1) {
            Intent(Intent.ACTION_SEND)
                .setType(files.first().mime.ifBlank { "*/*" })
                .putExtra(Intent.EXTRA_STREAM, files.first().uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            val uris = ArrayList(files.map { it.uri })
            val clip = ClipData.newUri(contentResolver, "Tap to Share Files", uris.first()).apply {
                for (i in 1 until uris.size) {
                    addItem(ClipData.Item(uris[i]))
                }
            }
            Intent(Intent.ACTION_SEND_MULTIPLE)
                .setType("*/*")
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .apply { clipData = clip }
        }
        runCatching { startActivity(Intent.createChooser(intent, "ファイルを共有")) }
    }

    private suspend fun ensureWifiPermissionSuspend(): Boolean {
        val missing = UiHelpers.missingWifiPermissions(this)
        if (missing.isEmpty()) return true
        val deferred = CompletableDeferred<Boolean>()
        permissionDeferred = deferred
        permissionLauncher.launch(missing)
        return deferred.await()
    }

    private fun showError(msg: String, primaryAction: StageAction? = null) {
        TransferForegroundService.stop(this)
        HapticFeedbackEngine.playError(this)
        SoundEffectEngine.playErrorTone(this)
        stage.showError("エラー", msg)
        stage.configureActions(
            primary = primaryAction,
            closeText = "閉じる",
            onClose = { cancelAndClose() }
        )
    }

    private fun cancelAndClose() {
        autoCloseHandler.removeCallbacksAndMessages(null)
        demoJob?.cancel()
        receiveJob?.cancel()
        TransferForegroundService.stop(this)
        finishAnimated()
    }

    private fun finishAnimated() {
        if (isClosing || isFinishing) return
        isClosing = true
        stage.playExit {
            disableWindowTransitions()
            finish()
        }
    }

    private fun disableWindowTransitions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    private fun hasNfcTag(intent: Intent): Boolean {
        @Suppress("DEPRECATION")
        val tag = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        }
        return tag != null
    }

    private fun extractNdefMessages(intent: Intent): List<NdefMessage> {
        @Suppress("DEPRECATION")
        val raw = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, android.os.Parcelable::class.java)
        } else {
            intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
        } ?: return emptyList()
        return raw.mapNotNull { it as? NdefMessage }
    }
}
