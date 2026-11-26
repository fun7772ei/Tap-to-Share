package fun7772ei.taptoshare

import android.app.Activity
import android.content.ComponentName
import android.nfc.NfcAdapter
import android.nfc.cardemulation.CardEmulation
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

class NfcBroadcastController(
    private val activity: Activity,
    private val onLog: (String) -> Unit = {}
) {
    companion object {
        private const val TAG = "TapShareNFC"
        private const val POST_READ_MUTE_FALLBACK_MS = 80L
    }

    private val nfcAdapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)
    private val cardEmulation: CardEmulation? = nfcAdapter?.let { CardEmulation.getInstance(it) }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ownerToken = Any()

    private var pendingMuteRunnable: Runnable? = null
    private var discoveryOverridden = false
    private var isResumed = false

    var isBroadcasting: Boolean = false
        private set

    var onReadFinished: ((Int) -> Unit)? = null
    var onMuted: (() -> Unit)? = null

    val isNfcSupported: Boolean
        get() = nfcAdapter != null && cardEmulation != null

    val isNfcEnabled: Boolean
        get() = nfcAdapter?.isEnabled == true

    fun attachListeners() {
        NdefPayloadStore.registerCallbacks(
            ownerToken = ownerToken,
            onRead = { count ->
                activity.runOnUiThread {
                    onLog("NFC読取完了 (count=$count)")
                    onReadFinished?.invoke(count)
                    if (isBroadcasting) {
                        schedulePostReadMute(POST_READ_MUTE_FALLBACK_MS)
                    }
                }
            },
            onDeactivated = {
                activity.runOnUiThread {
                    if (isBroadcasting) {
                        muteListenAfterRead()
                    }
                }
            }
        )
    }

    fun startBroadcast(ndefJson: String, summary: String) {
        NdefPayloadStore.setJsonPayload(ndefJson, summary, ownerToken)
        isBroadcasting = true
        if (isResumed) {
            activate()
        }
    }

    fun stopBroadcast() {
        pendingMuteRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingMuteRunnable = null
        isBroadcasting = false
        NdefPayloadStore.clear(ownerToken)
        deactivate()
    }

    fun onResume() {
        isResumed = true
        attachListeners()
        if (isBroadcasting) {
            activate()
        }
    }

    fun onPause() {
        isResumed = false
        NdefPayloadStore.unregisterCallbacks(ownerToken)
        deactivate()
    }

    fun release() {
        pendingMuteRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingMuteRunnable = null
        NdefPayloadStore.unregisterCallbacks(ownerToken)
        NdefPayloadStore.clear(ownerToken)
        isBroadcasting = false
        deactivate()
    }

    private fun activate() {
        pendingMuteRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingMuteRunnable = null
        val adapter = nfcAdapter
        val ce = cardEmulation
        if (adapter == null || ce == null) {
            onLog("NFC未対応端末")
            return
        }
        val prefOk = runCatching {
            ce.setPreferredService(activity, ComponentName(activity, TapNdefHceService::class.java))
        }.getOrElse { e ->
            Log.e(TAG, "setPreferredService failed", e)
            false
        }
        var discOk = "N/A"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            discOk = runCatching {
                adapter.setDiscoveryTechnology(
                    activity,
                    NfcAdapter.FLAG_READER_DISABLE,
                    NfcAdapter.FLAG_LISTEN_NFC_PASSIVE_A
                )
                discoveryOverridden = true
                "OK"
            }.getOrElse { e ->
                Log.e(TAG, "setDiscoveryTechnology failed", e)
                "ERR(${e.message})"
            }
        }
        val msg = "HCE_ACTIVE setPreferred=$prefOk discTech=$discOk"
        Log.i(TAG, msg)
        onLog(msg)
    }

    private fun schedulePostReadMute(delayMs: Long) {
        pendingMuteRunnable?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable {
            pendingMuteRunnable = null
            if (isBroadcasting) {
                muteListenAfterRead()
            }
        }
        pendingMuteRunnable = r
        mainHandler.postDelayed(r, delayMs)
    }

    private fun muteListenAfterRead() {
        pendingMuteRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingMuteRunnable = null
        isBroadcasting = false
        NdefPayloadStore.clear(ownerToken)
        runCatching { cardEmulation?.unsetPreferredService(activity) }

        var result = "N/A"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            result = runCatching {
                nfcAdapter?.setDiscoveryTechnology(
                    activity,
                    NfcAdapter.FLAG_READER_DISABLE,
                    NfcAdapter.FLAG_LISTEN_DISABLE
                )
                discoveryOverridden = true
                "OK"
            }.getOrElse { e ->
                Log.e(TAG, "muteListenAfterRead failed", e)
                "ERR(${e.message})"
            }
        }
        val msg = "NFC_MUTED (Wallet誤起動防止) result=$result"
        Log.i(TAG, msg)
        onLog(msg)
        onMuted?.invoke()
    }

    private fun deactivate() {
        pendingMuteRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingMuteRunnable = null
        runCatching { cardEmulation?.unsetPreferredService(activity) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM && discoveryOverridden) {
            discoveryOverridden = false
            runCatching { nfcAdapter?.resetDiscoveryTechnology(activity) }
        }
    }
}
