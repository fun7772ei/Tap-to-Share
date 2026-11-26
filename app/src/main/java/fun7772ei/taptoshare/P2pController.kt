package fun7772ei.taptoshare

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.security.SecureRandom
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class P2pGroupInfo(
    val ssid: String,
    val passphrase: String,
    val frequency: Int,
    val goIp: String
)

data class P2pClientConnection(
    val goIp: String,
    val localP2pIpv4: String?,
    val connectMs: Long
)

class P2pController(context: Context) {
    companion object {
        private const val TAG = "TapToShare"
        private const val ALPHANUM = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789"
    }

    private val appContext = context.applicationContext
    private val manager: WifiP2pManager? =
        appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private val channel: WifiP2pManager.Channel? =
        manager?.initialize(appContext, Looper.getMainLooper(), null)
    private val random = SecureRandom()
    private val groupMutex = Mutex()

    val isSupported: Boolean
        get() = manager != null && channel != null

    private fun requireP2p(): Pair<WifiP2pManager, WifiP2pManager.Channel> {
        val mgr = manager ?: throw IllegalStateException("この端末は Wi-Fi Direct に対応していません")
        val ch = channel ?: throw IllegalStateException("Wi-Fi Direct の初期化に失敗しました")
        return mgr to ch
    }

    @SuppressLint("MissingPermission")
    suspend fun createDirectGroup(): P2pGroupInfo = groupMutex.withLock {
        val (mgr, ch) = requireP2p()
        cancelConnectQuietly(mgr, ch)
        removeGroupQuietly(mgr, ch)
        delay(200)

        val suffix = randomChars(2)
        val ssid = "DIRECT-$suffix-TapShare"
        val pass = randomChars(10)

        val config = WifiP2pConfig.Builder()
            .setNetworkName(ssid)
            .setPassphrase(pass)
            .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_AUTO)
            .enablePersistentMode(false)
            .build()

        try {
            callCreateGroup(mgr, ch, config)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "createGroup attempt 1 failed: ${e.message}; retrying")
            delay(500)
            callCreateGroup(mgr, ch, config)
        }

        withTimeout(10_000) {
            while (true) {
                val group = requestGroupInfoSuspend(mgr, ch)
                val info = requestConnectionInfoSuspend(mgr, ch)
                if (group != null && info != null && info.groupFormed) {
                    val goIp = info.groupOwnerAddress?.hostAddress ?: "192.168.49.1"
                    val actualSsid = group.networkName ?: ssid
                    val actualPass = group.passphrase ?: pass
                    val freq = group.frequency
                    Log.d(TAG, "P2P GO active freq=$freq")
                    return@withTimeout P2pGroupInfo(actualSsid, actualPass, freq, goIp)
                }
                delay(200)
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun connectToDirectGroup(
        ssid: String,
        passphrase: String,
        frequency: Int = 0
    ): P2pClientConnection {
        val (mgr, ch) = requireP2p()
        val startMs = System.currentTimeMillis()
        groupMutex.withLock {
            cancelConnectQuietly(mgr, ch)
            removeGroupQuietly(mgr, ch)
        }
        delay(200)

        val connectedSignal = CompletableDeferred<Unit>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION) {
                    @Suppress("DEPRECATION")
                    val p2pInfo = intent.getParcelableExtra<WifiP2pInfo>(WifiP2pManager.EXTRA_WIFI_P2P_INFO)
                    if (p2pInfo?.groupFormed == true && p2pInfo.groupOwnerAddress != null) {
                        connectedSignal.complete(Unit)
                    }
                }
            }
        }

        val filter = IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_EXPORTED)

        try {
            val primaryConfig = WifiP2pConfig.Builder()
                .setNetworkName(ssid)
                .setPassphrase(passphrase)
                .enablePersistentMode(false)
                .apply {
                    if (frequency > 0) {
                        setGroupOperatingFrequency(frequency)
                    }
                }
                .build()

            try {
                callConnect(mgr, ch, primaryConfig)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "P2P connect attempt 1 failed: ${e.message}; cancelling & retrying without fixed frequency")
                cancelConnectQuietly(mgr, ch)
                delay(500)
                val fallbackConfig = WifiP2pConfig.Builder()
                    .setNetworkName(ssid)
                    .setPassphrase(passphrase)
                    .enablePersistentMode(false)
                    .build()
                callConnect(mgr, ch, fallbackConfig)
            }

            return withTimeout(20_000) {
                while (true) {
                    val info = requestConnectionInfoSuspend(mgr, ch)
                    if (info != null && info.groupFormed && info.groupOwnerAddress != null) {
                        val goIp = info.groupOwnerAddress!!.hostAddress ?: "192.168.49.1"
                        // Wait briefly for local p2p interface IP assignment
                        var localP2pIp = WifiStatusChecker.findInterfaceIpv4("p2p")
                        var tries = 0
                        while (localP2pIp == null && tries < 10) {
                            delay(100)
                            localP2pIp = WifiStatusChecker.findInterfaceIpv4("p2p")
                            tries++
                        }
                        val elapsed = System.currentTimeMillis() - startMs
                        Log.d(TAG, "P2P connected freq=$frequency in ${elapsed}ms")
                        return@withTimeout P2pClientConnection(goIp, localP2pIp, elapsed)
                    }
                    if (!connectedSignal.isCompleted) {
                        withTimeoutOrNull(200) { connectedSignal.await() }
                    } else {
                        delay(200)
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                error("unreachable")
            }
        } finally {
            try {
                appContext.unregisterReceiver(receiver)
            } catch (_: Exception) {
            }
        }
    }

    suspend fun cleanupGroup() = groupMutex.withLock {
        val mgr = manager ?: return@withLock
        val ch = channel ?: return@withLock
        cancelConnectQuietly(mgr, ch)
        removeGroupQuietly(mgr, ch)
    }

    fun close() {
        try {
            channel?.close()
        } catch (_: Exception) {
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun callCreateGroup(
        mgr: WifiP2pManager,
        ch: WifiP2pManager.Channel,
        config: WifiP2pConfig
    ) {
        suspendCancellableCoroutine { cont ->
            mgr.createGroup(ch, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    if (cont.isActive) cont.resume(Unit)
                }

                override fun onFailure(reason: Int) {
                    if (cont.isActive) {
                        cont.resumeWithException(IllegalStateException("createGroup failed reason=$reason"))
                    }
                }
            })
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun callConnect(
        mgr: WifiP2pManager,
        ch: WifiP2pManager.Channel,
        config: WifiP2pConfig
    ) {
        suspendCancellableCoroutine { cont ->
            mgr.connect(ch, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    if (cont.isActive) cont.resume(Unit)
                }

                override fun onFailure(reason: Int) {
                    if (cont.isActive) {
                        cont.resumeWithException(IllegalStateException("connect failed reason=$reason"))
                    }
                }
            })
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun cancelConnectQuietly(
        mgr: WifiP2pManager,
        ch: WifiP2pManager.Channel
    ) {
        suspendCancellableCoroutine { cont ->
            try {
                mgr.cancelConnect(ch, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        if (cont.isActive) cont.resume(Unit)
                    }

                    override fun onFailure(reason: Int) {
                        if (cont.isActive) cont.resume(Unit)
                    }
                })
            } catch (_: Exception) {
                if (cont.isActive) cont.resume(Unit)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun removeGroupQuietly(
        mgr: WifiP2pManager,
        ch: WifiP2pManager.Channel
    ) {
        suspendCancellableCoroutine { cont ->
            try {
                mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        if (cont.isActive) cont.resume(Unit)
                    }

                    override fun onFailure(reason: Int) {
                        if (cont.isActive) cont.resume(Unit)
                    }
                })
            } catch (_: Exception) {
                if (cont.isActive) cont.resume(Unit)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestGroupInfoSuspend(
        mgr: WifiP2pManager,
        ch: WifiP2pManager.Channel
    ): WifiP2pGroup? {
        return suspendCancellableCoroutine { cont ->
            mgr.requestGroupInfo(ch) { group ->
                if (cont.isActive) cont.resume(group)
            }
        }
    }

    private suspend fun requestConnectionInfoSuspend(
        mgr: WifiP2pManager,
        ch: WifiP2pManager.Channel
    ): WifiP2pInfo? {
        return suspendCancellableCoroutine { cont ->
            mgr.requestConnectionInfo(ch) { info ->
                if (cont.isActive) cont.resume(info)
            }
        }
    }

    private fun randomChars(len: Int): String {
        return buildString(len) {
            repeat(len) {
                append(ALPHANUM[random.nextInt(ALPHANUM.length)])
            }
        }
    }
}
