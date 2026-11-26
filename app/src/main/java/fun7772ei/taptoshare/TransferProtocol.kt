package fun7772ei.taptoshare

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

object NetworkAddressValidator {
    fun isPrivateIpv4(ip: String?): Boolean {
        val raw = ip?.trim() ?: return false
        val parts = raw.split('.')
        if (parts.size != 4) return false
        val octets = IntArray(4)
        for (i in 0..3) {
            val part = parts[i]
            if (part.isEmpty() || part.length > 3 || !part.all { it.isDigit() }) return false
            val v = part.toIntOrNull() ?: return false
            if (v !in 0..255) return false
            octets[i] = v
        }
        val a = octets[0]
        val b = octets[1]
        return when {
            a == 10 -> true
            a == 172 && b in 16..31 -> true
            a == 192 && b == 168 -> true
            else -> false
        }
    }

    fun isValidPort(port: Int): Boolean = port in 1..65535

    fun sameIpv4Subnet24(ipA: String?, ipB: String?): Boolean {
        if (ipA.isNullOrBlank() || ipB.isNullOrBlank()) return false
        val a = ipA.trim().split('.')
        val b = ipB.trim().split('.')
        if (a.size != 4 || b.size != 4) return false
        return a[0] == b[0] && a[1] == b[1] && a[2] == b[2]
    }
}

data class TapPayload(
    val v: Int = 2,
    val t: String,
    val s: String,
    val d: String? = null,
    val cnt: Int = 1,
    val total: Long = 0L,
    val preview: String? = null,
    val isLargeText: Boolean = false,
    val tok: String? = null,
    val key: String? = null,
    val lanIp: String? = null,
    val port: Int = 0,
    val p2pSsid: String? = null,
    val p2pPass: String? = null,
    val p2pIp: String? = null,
    val p2pFreq: Int = 0,
    val forceP2p: Boolean = false
) {
    fun toJson(): String {
        return JSONObject().apply {
            put("v", v)
            put("t", t)
            put("s", s)
            if (d != null) put("d", d)
            if (t == "file") {
                put("cnt", cnt)
                if (total > 0L) put("total", total)
                if (preview != null) put("preview", preview)
                if (isLargeText) put("isLargeText", true)
                if (tok != null) put("tok", tok)
                if (key != null) put("key", key)
                if (lanIp != null) put("lanIp", lanIp)
                if (port > 0) put("port", port)
                if (p2pSsid != null) put("p2pSsid", p2pSsid)
                if (p2pPass != null) put("p2pPass", p2pPass)
                if (p2pIp != null) put("p2pIp", p2pIp)
                if (p2pFreq > 0) put("p2pFreq", p2pFreq)
                if (forceP2p) put("forceP2p", true)
            }
        }.toString()
    }

    companion object {
        fun fromJson(json: String): TapPayload {
            val obj = JSONObject(json)
            val preview = when {
                obj.has("preview") && !obj.isNull("preview") -> obj.getString("preview")
                obj.has("name") && !obj.isNull("name") -> obj.getString("name")
                else -> null
            }
            val total = when {
                obj.has("total") -> obj.optLong("total", 0L)
                else -> obj.optLong("size", 0L)
            }
            return TapPayload(
                v = obj.optInt("v", 1),
                t = obj.getString("t"),
                s = obj.optString("s", "unknown"),
                d = if (obj.has("d") && !obj.isNull("d")) obj.getString("d") else null,
                cnt = obj.optInt("cnt", 1),
                total = total,
                preview = preview,
                isLargeText = obj.optBoolean("isLargeText", false),
                tok = if (obj.has("tok") && !obj.isNull("tok")) obj.getString("tok") else null,
                key = if (obj.has("key") && !obj.isNull("key")) obj.getString("key") else null,
                lanIp = if (obj.has("lanIp") && !obj.isNull("lanIp")) obj.getString("lanIp") else null,
                port = obj.optInt("port", 0),
                p2pSsid = if (obj.has("p2pSsid") && !obj.isNull("p2pSsid")) obj.getString("p2pSsid") else null,
                p2pPass = if (obj.has("p2pPass") && !obj.isNull("p2pPass")) obj.getString("p2pPass") else null,
                p2pIp = if (obj.has("p2pIp") && !obj.isNull("p2pIp")) obj.getString("p2pIp") else null,
                p2pFreq = obj.optInt("p2pFreq", 0),
                forceP2p = obj.optBoolean("forceP2p", false)
            )
        }
    }
}

data class SharedItem(
    val name: String,
    val relativeDir: String = "",
    val size: Long,
    val mime: String,
    val uri: Uri? = null,
    val syntheticBytes: Long = 0L,
    val textBytes: ByteArray? = null
)

data class ManifestItem(
    val name: String,
    val relativeDir: String,
    val size: Long,
    val mime: String
)

data class TransferManifest(
    val isLargeText: Boolean,
    val items: List<ManifestItem>
) {
    fun toJson(): String {
        val arr = JSONArray()
        items.forEach { item ->
            arr.put(JSONObject().apply {
                put("name", item.name)
                put("relDir", item.relativeDir)
                put("size", item.size)
                put("mime", item.mime)
            })
        }
        return JSONObject().apply {
            put("isLargeText", isLargeText)
            put("items", arr)
        }.toString()
    }

    companion object {
        fun fromJson(json: String): TransferManifest {
            val obj = JSONObject(json)
            val arr = obj.getJSONArray("items")
            val items = buildList(arr.length()) {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        ManifestItem(
                            name = o.getString("name"),
                            relativeDir = o.optString("relDir", ""),
                            size = o.optLong("size", 0L),
                            mime = o.optString("mime", "application/octet-stream")
                        )
                    )
                }
            }
            return TransferManifest(
                isLargeText = obj.optBoolean("isLargeText", false),
                items = items
            )
        }
    }
}

data class TransferProgress(
    val fileIndex: Int,
    val fileCount: Int,
    val fileName: String,
    val transferredBytes: Long,
    val totalBytes: Long,
    val currentMBps: Double
)

data class WifiSnapshot(
    val hasWifiTransport: Boolean,
    val hasInternet: Boolean,
    val isValidated: Boolean,
    val isActiveWifi: Boolean,
    val wlanIpv4: String?,
    val p2pIpv4: String?
) {
    fun summary(): String {
        return "WIFI[net=${yesNo(hasWifiTransport)},inet=${yesNo(hasInternet)},valid=${yesNo(isValidated)},active=${yesNo(isActiveWifi)},wlan=${wlanIpv4 ?: "none"},p2p=${p2pIpv4 ?: "none"}]"
    }

    private fun yesNo(b: Boolean) = if (b) "Y" else "N"
}

object WifiStatusChecker {
    fun capture(context: Context): WifiSnapshot {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        var hasWifi = false
        var hasInternet = false
        var isValidated = false

        @Suppress("DEPRECATION")
        val networks = cm.allNetworks
        for (net in networks) {
            val caps = cm.getNetworkCapabilities(net) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                val netInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                val netValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                if (!hasWifi || netInternet) {
                    hasWifi = true
                    hasInternet = hasInternet || netInternet
                    isValidated = isValidated || netValidated
                }
            }
        }

        val activeCaps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val isActiveWifi = activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

        return WifiSnapshot(
            hasWifiTransport = hasWifi,
            hasInternet = hasInternet,
            isValidated = isValidated,
            isActiveWifi = isActiveWifi,
            wlanIpv4 = findInterfaceIpv4(listOf("wlan", "eth")),
            p2pIpv4 = findInterfaceIpv4(listOf("p2p")) ?: findIpv4ByAddressPrefix("192.168.49.")
        )
    }

    fun findInterfaceIpv4(prefix: String): String? = findInterfaceIpv4(listOf(prefix))

    fun findInterfaceIpv4(prefixes: List<String>): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            val ifaceList = interfaces.toList()
            for (prefix in prefixes) {
                for (iface in ifaceList) {
                    if (!iface.isUp || iface.isLoopback) continue
                    if (!iface.name.startsWith(prefix, ignoreCase = true)) continue
                    val addrs = iface.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            return addr.hostAddress
                        }
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun findIpv4ByAddressPrefix(addressPrefix: String): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    val host = (addr as? Inet4Address)?.hostAddress
                    if (host != null && host.startsWith(addressPrefix)) {
                        return host
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }
}

class LocalFileServer(
    private val items: List<SharedItem>,
    private val token: String,
    private val keyBytes: ByteArray,
    private val isLargeText: Boolean = false,
    private val streamOpener: (SharedItem) -> InputStream,
    private val onProgress: (TransferProgress) -> Unit = {},
    private val onClientDone: (String) -> Unit = {},
    private val onStatus: (String) -> Unit = {}
) {
    companion object {
        private const val TAG = "TapToShare"
        private const val MAX_REQUEST_HEADER_LINES = 64
        private const val MAX_REQUEST_HEADER_BYTES = 8 * 1024
        const val BENCHMARK_SIZE_BYTES = 20L * 1024L * 1024L
        const val BENCHMARK_NAME = "__benchmark_20mb__.bin"

        fun createAndroid(
            context: Context,
            items: List<SharedItem>,
            token: String,
            keyBytes: ByteArray,
            isLargeText: Boolean = false,
            onProgress: (TransferProgress) -> Unit = {},
            onClientDone: (String) -> Unit = {},
            onStatus: (String) -> Unit = {}
        ): LocalFileServer {
            val appCtx = context.applicationContext
            return LocalFileServer(
                items = items,
                token = token,
                keyBytes = keyBytes,
                isLargeText = isLargeText,
                streamOpener = { item ->
                    when {
                        item.textBytes != null -> ByteArrayInputStream(item.textBytes)
                        item.uri != null -> appCtx.contentResolver.openInputStream(item.uri)
                            ?: throw IOException("Unable to open ${item.uri}")
                        item.syntheticBytes > 0L -> SyntheticInputStream(item.syntheticBytes)
                        else -> ByteArrayInputStream(ByteArray(0))
                    }
                },
                onProgress = onProgress,
                onClientDone = onClientDone,
                onStatus = onStatus
            )
        }
    }

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private val activeClients: MutableSet<Socket> =
        Collections.newSetFromMap(ConcurrentHashMap<Socket, Boolean>())

    private val totalBatchBytes: Long = items.sumOf { maxOf(0L, it.size) }
    private val lock = Any()
    private var sentCumulativeBytes = 0L
    private var transferStartMs = 0L
    private var lastProgressEmitMs = 0L

    val port: Int
        get() = serverSocket?.localPort ?: 0

    fun start(scope: CoroutineScope): Int {
        stop()
        val ss = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(0))
        }
        serverSocket = ss
        val boundPort = ss.localPort
        Log.d(TAG, "LocalFileServer listening port=$boundPort files=${items.size} total=$totalBatchBytes")

        acceptJob = scope.launch(Dispatchers.IO) {
            while (isActive && !ss.isClosed) {
                try {
                    val client = ss.accept()
                    activeClients.add(client)
                    launch(Dispatchers.IO) {
                        try {
                            handleClient(client)
                        } catch (err: Exception) {
                            Log.w(TAG, "Server client error: ${err.message}")
                        } finally {
                            activeClients.remove(client)
                            runCatching { client.close() }
                        }
                    }
                } catch (e: IOException) {
                    if (isActive && !ss.isClosed) {
                        Log.w(TAG, "Server accept error: ${e.message}")
                    }
                    break
                }
            }
        }
        return boundPort
    }

    private fun handleClient(socket: Socket) {
        socket.use { sock ->
            sock.soTimeout = 30_000
            sock.sendBufferSize = 256 * 1024
            sock.tcpNoDelay = true

            val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.US_ASCII))
            val requestLine = reader.readLine() ?: return
            var headerBytes = requestLine.length + 2
            var headerLines = 1
            var line: String?
            do {
                line = reader.readLine()
                if (!line.isNullOrEmpty()) {
                    headerLines++
                    headerBytes += line.length + 2
                    if (headerLines > MAX_REQUEST_HEADER_LINES || headerBytes > MAX_REQUEST_HEADER_BYTES) {
                        return
                    }
                }
            } while (!line.isNullOrEmpty())

            val remote = sock.inetAddress?.hostAddress ?: "unknown"
            val parts = requestLine.split(" ")
            val method = parts.getOrNull(0) ?: ""
            val pathAndQuery = parts.getOrNull(1) ?: ""
            val path = pathAndQuery.substringBefore("?")
            val reqTok = pathAndQuery.substringAfter("tok=", "").substringBefore("&")

            val out = BufferedOutputStream(sock.getOutputStream(), 65536)
            if (reqTok != token) {
                writeStatus(out, "403 Forbidden", ByteArray(0))
                onStatus("Rejected $remote (bad token)")
                return
            }

            when {
                method == "GET" && path == "/manifest" -> {
                    synchronized(lock) {
                        sentCumulativeBytes = 0L
                        transferStartMs = System.currentTimeMillis()
                        lastProgressEmitMs = 0L
                    }
                    val manifest = TransferManifest(
                        isLargeText = isLargeText,
                        items = items.map { ManifestItem(it.name, it.relativeDir, it.size, it.mime) }
                    )
                    val enc = ChunkCrypto.encryptBytes(
                        keyBytes,
                        manifest.toJson().toByteArray(Charsets.UTF_8),
                        ChunkCrypto.buildAad(ChunkCrypto.CONTEXT_MANIFEST)
                    )
                    writeStatus(out, "200 OK", enc)
                    onStatus("Manifest sent to $remote (${items.size} items)")
                }

                method == "GET" && path.startsWith("/file/") -> {
                    val idx = path.removePrefix("/file/").toIntOrNull()
                    if (idx == null || idx !in items.indices) {
                        writeStatus(out, "404 Not Found", ByteArray(0))
                        return
                    }
                    val item = items[idx]
                    val openedStream = try {
                        streamOpener(item)
                    } catch (e: Exception) {
                        val status = if (e is FileNotFoundException) "404 Not Found" else "500 Internal Server Error"
                        Log.w(TAG, "Failed to open stream for item[$idx] (${item.name}): ${e.message}", e)
                        writeStatus(out, status, (e.message ?: status).toByteArray(Charsets.UTF_8))
                        onStatus("Failed to open ${item.name}: ${e.message}")
                        return
                    }

                    openedStream.use { input ->
                        val headers = "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nConnection: close\r\n\r\n"
                        out.write(headers.toByteArray(Charsets.US_ASCII))
                        out.flush()

                        BufferedInputStream(input, 65536).use { bis ->
                            ChunkCrypto.writeEncryptedStream(
                                keyBytes = keyBytes,
                                input = bis,
                                output = out,
                                context = ChunkCrypto.CONTEXT_FILE,
                                fileIndex = idx
                            ) { delta ->
                                reportServerProgress(idx, item.name, delta)
                            }
                        }
                    }
                    out.flush()
                    emitFinalItemProgress(idx, item.name)
                }

                method == "POST" && path == "/done" -> {
                    writeStatus(out, "200 OK", "OK".toByteArray(Charsets.US_ASCII))
                    onStatus("ACK received from $remote")
                    onClientDone(remote)
                }

                else -> {
                    writeStatus(out, "404 Not Found", ByteArray(0))
                }
            }
        }
    }

    private fun reportServerProgress(idx: Int, fileName: String, deltaBytes: Long) {
        val now = System.currentTimeMillis()
        var snapshot: TransferProgress? = null
        synchronized(lock) {
            if (transferStartMs == 0L) transferStartMs = now
            sentCumulativeBytes += deltaBytes
            if (now - lastProgressEmitMs >= 100L) {
                lastProgressEmitMs = now
                val elapsedSec = maxOf(0.001, (now - transferStartMs) / 1000.0)
                val mBps = sentCumulativeBytes.toDouble() / (1024.0 * 1024.0) / elapsedSec
                snapshot = TransferProgress(idx, items.size, fileName, sentCumulativeBytes, totalBatchBytes, mBps)
            }
        }
        snapshot?.let(onProgress)
    }

    private fun emitFinalItemProgress(idx: Int, fileName: String) {
        val snap = synchronized(lock) {
            val now = System.currentTimeMillis()
            val elapsedSec = maxOf(0.001, (now - transferStartMs) / 1000.0)
            val mBps = sentCumulativeBytes.toDouble() / (1024.0 * 1024.0) / elapsedSec
            TransferProgress(idx, items.size, fileName, sentCumulativeBytes, totalBatchBytes, mBps)
        }
        onProgress(snap)
    }

    private fun writeStatus(out: BufferedOutputStream, status: String, body: ByteArray) {
        val headers = "HTTP/1.1 $status\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        out.write(headers.toByteArray(Charsets.US_ASCII))
        if (body.isNotEmpty()) {
            out.write(body)
        }
        out.flush()
    }

    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        val snapshot = activeClients.toList()
        activeClients.clear()
        for (client in snapshot) {
            runCatching { client.close() }
        }
    }
}

private class SyntheticInputStream(private var remaining: Long) : InputStream() {
    private val pattern = ByteArray(65536) { (it and 0xFF).toByte() }
    private var patternPos = 0

    override fun read(): Int {
        if (remaining <= 0L) return -1
        remaining--
        val b = pattern[patternPos].toInt() and 0xFF
        patternPos = (patternPos + 1) % pattern.size
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (remaining <= 0L) return -1
        val count = minOf(len.toLong(), remaining).toInt()
        var written = 0
        while (written < count) {
            val chunk = minOf(count - written, pattern.size - patternPos)
            System.arraycopy(pattern, patternPos, b, off + written, chunk)
            patternPos = (patternPos + chunk) % pattern.size
            written += chunk
        }
        remaining -= count
        return count
    }
}

data class DownloadedEntry<T>(
    val manifestItem: ManifestItem,
    val bytesWritten: Long,
    val saved: T?
)

data class BatchTransferResult<T>(
    val route: String,
    val connectMs: Long,
    val transferMs: Long,
    val totalBytes: Long,
    val mBps: Double,
    val mbps: Double,
    val manifest: TransferManifest,
    val entries: List<DownloadedEntry<T>>,
    val largeTextContent: String?
)

object LocalFileClient {
    private const val TAG = "TapToShare"
    const val MAX_MANIFEST_BYTES = 2 * 1024 * 1024
    const val MAX_LARGE_TEXT_MEMORY_BYTES = 1 * 1024 * 1024
    private const val MAX_HEADER_BYTES = 8 * 1024
    private const val MAX_HEADER_LINES = 64

    suspend fun <T> downloadBatch(
        host: String,
        port: Int,
        token: String,
        keyBytes: ByteArray,
        bindLocalIp: String?,
        routeName: String,
        connectTimeoutMs: Int = 5000,
        onProgress: (TransferProgress) -> Unit = {},
        saveHandler: suspend (ManifestItem, suspend (OutputStream) -> Long) -> Pair<Long, T?>
    ): BatchTransferResult<T> {
        val connectStart = System.currentTimeMillis()
        val manifest = fetchManifest(host, port, token, keyBytes, bindLocalIp, connectTimeoutMs)
        val connectMs = System.currentTimeMillis() - connectStart

        val totalExpected = manifest.items.sumOf { maxOf(0L, it.size) }
        val xferStart = System.currentTimeMillis()
        var receivedCumulative = 0L
        var lastEmitMs = 0L

        val entries = mutableListOf<DownloadedEntry<T>>()
        var largeTextResult: String? = null

        for ((idx, item) in manifest.items.withIndex()) {
            currentCoroutineContext().ensureActive()

            if (manifest.isLargeText && idx == 0 && item.size in 0L..MAX_LARGE_TEXT_MEMORY_BYTES.toLong()) {
                val baos = ByteArrayOutputStream()
                val boundedOut = object : OutputStream() {
                    private var written = 0L
                    override fun write(b: Int) {
                        written++
                        if (written > MAX_LARGE_TEXT_MEMORY_BYTES) {
                            throw IOException("Large text exceeds in-memory limit ($MAX_LARGE_TEXT_MEMORY_BYTES bytes)")
                        }
                        baos.write(b)
                    }

                    override fun write(b: ByteArray, off: Int, len: Int) {
                        written += len
                        if (written > MAX_LARGE_TEXT_MEMORY_BYTES) {
                            throw IOException("Large text exceeds in-memory limit ($MAX_LARGE_TEXT_MEMORY_BYTES bytes)")
                        }
                        baos.write(b, off, len)
                    }
                }
                val bytes = downloadSingleFile(
                    host = host,
                    port = port,
                    index = idx,
                    token = token,
                    keyBytes = keyBytes,
                    bindLocalIp = bindLocalIp,
                    connectTimeoutMs = connectTimeoutMs,
                    output = boundedOut
                ) { delta ->
                    receivedCumulative += delta
                    val now = System.currentTimeMillis()
                    if (now - lastEmitMs >= 100L) {
                        lastEmitMs = now
                        val sec = maxOf(0.001, (now - xferStart) / 1000.0)
                        onProgress(
                            TransferProgress(
                                fileIndex = idx,
                                fileCount = manifest.items.size,
                                fileName = item.name,
                                transferredBytes = receivedCumulative,
                                totalBytes = totalExpected,
                                currentMBps = receivedCumulative.toDouble() / (1024.0 * 1024.0) / sec
                            )
                        )
                    }
                }
                largeTextResult = baos.toString(Charsets.UTF_8.name())
                entries.add(DownloadedEntry(item, bytes, null))
            } else {
                val (bytes, savedObj) = saveHandler(item) { out ->
                    downloadSingleFile(
                        host = host,
                        port = port,
                        index = idx,
                        token = token,
                        keyBytes = keyBytes,
                        bindLocalIp = bindLocalIp,
                        connectTimeoutMs = connectTimeoutMs,
                        output = out
                    ) { delta ->
                        receivedCumulative += delta
                        val now = System.currentTimeMillis()
                        if (now - lastEmitMs >= 100L) {
                            lastEmitMs = now
                            val sec = maxOf(0.001, (now - xferStart) / 1000.0)
                            onProgress(
                                TransferProgress(
                                    fileIndex = idx,
                                    fileCount = manifest.items.size,
                                    fileName = item.name,
                                    transferredBytes = receivedCumulative,
                                    totalBytes = totalExpected,
                                    currentMBps = receivedCumulative.toDouble() / (1024.0 * 1024.0) / sec
                                )
                            )
                        }
                    }
                }
                entries.add(DownloadedEntry(item, bytes, savedObj))
            }
        }

        val transferMs = maxOf(1L, System.currentTimeMillis() - xferStart)
        val sec = transferMs.toDouble() / 1000.0
        val mBps = receivedCumulative.toDouble() / (1024.0 * 1024.0) / sec
        val mbps = receivedCumulative.toDouble() * 8.0 / 1_000_000.0 / sec

        onProgress(
            TransferProgress(
                fileIndex = maxOf(0, manifest.items.size - 1),
                fileCount = manifest.items.size,
                fileName = manifest.items.lastOrNull()?.name ?: "",
                transferredBytes = receivedCumulative,
                totalBytes = maxOf(totalExpected, receivedCumulative),
                currentMBps = mBps
            )
        )

        runCatching { sendDoneAck(host, port, token, bindLocalIp, connectTimeoutMs) }
            .onFailure { Log.w(TAG, "POST /done failed: ${it.message}") }

        return BatchTransferResult(
            route = routeName,
            connectMs = connectMs,
            transferMs = transferMs,
            totalBytes = receivedCumulative,
            mBps = mBps,
            mbps = mbps,
            manifest = manifest,
            entries = entries,
            largeTextContent = largeTextResult
        )
    }

    private suspend fun fetchManifest(
        host: String,
        port: Int,
        token: String,
        keyBytes: ByteArray,
        bindLocalIp: String?,
        connectTimeoutMs: Int
    ): TransferManifest {
        val sock = openBoundOrFallbackSocket(host, port, bindLocalIp, connectTimeoutMs)
        val cancelHandle = currentCoroutineContext().job.invokeOnCompletion { cause ->
            if (cause != null) runCatching { sock.close() }
        }
        try {
            sock.use { s ->
                s.soTimeout = 15_000
                s.tcpNoDelay = true
                val req = "GET /manifest?tok=$token HTTP/1.1\r\nHost: $host:$port\r\nConnection: close\r\n\r\n"
                s.getOutputStream().write(req.toByteArray(Charsets.US_ASCII))
                s.getOutputStream().flush()

                val rawIn = BufferedInputStream(s.getInputStream(), 16384)
                val headers = readHeaderLines(rawIn)
                val status = headers.firstOrNull() ?: throw IOException("Empty HTTP response")
                if (!status.contains(" 200 ")) throw IOException("Manifest HTTP error: $status")

                val contentLength = parseContentLength(headers)
                if (contentLength > MAX_MANIFEST_BYTES) {
                    throw IOException("Manifest exceeds size limit: $contentLength bytes (max=$MAX_MANIFEST_BYTES)")
                }
                val encBytes = if (contentLength >= 0) {
                    val buf = ByteArray(contentLength.toInt())
                    var off = 0
                    while (off < buf.size) {
                        val n = rawIn.read(buf, off, buf.size - off)
                        if (n < 0) break
                        off += n
                    }
                    buf.copyOf(off)
                } else {
                    val baos = ByteArrayOutputStream()
                    val chunk = ByteArray(8192)
                    var totalRead = 0
                    while (true) {
                        val n = rawIn.read(chunk)
                        if (n < 0) break
                        totalRead += n
                        if (totalRead > MAX_MANIFEST_BYTES) {
                            throw IOException("Manifest exceeds size limit (max=$MAX_MANIFEST_BYTES)")
                        }
                        baos.write(chunk, 0, n)
                    }
                    baos.toByteArray()
                }
                val plainJson = String(
                    ChunkCrypto.decryptBytes(
                        keyBytes,
                        encBytes,
                        ChunkCrypto.buildAad(ChunkCrypto.CONTEXT_MANIFEST)
                    ),
                    Charsets.UTF_8
                )
                return TransferManifest.fromJson(plainJson)
            }
        } finally {
            cancelHandle.dispose()
        }
    }

    private suspend fun downloadSingleFile(
        host: String,
        port: Int,
        index: Int,
        token: String,
        keyBytes: ByteArray,
        bindLocalIp: String?,
        connectTimeoutMs: Int,
        output: OutputStream,
        onChunkPlainBytes: (Long) -> Unit
    ): Long {
        val sock = openBoundOrFallbackSocket(host, port, bindLocalIp, connectTimeoutMs)
        val cancelHandle = currentCoroutineContext().job.invokeOnCompletion { cause ->
            if (cause != null) runCatching { sock.close() }
        }
        try {
            sock.use { s ->
                s.soTimeout = 30_000
                s.receiveBufferSize = 256 * 1024
                s.tcpNoDelay = true

                val req = "GET /file/$index?tok=$token HTTP/1.1\r\nHost: $host:$port\r\nConnection: close\r\n\r\n"
                s.getOutputStream().write(req.toByteArray(Charsets.US_ASCII))
                s.getOutputStream().flush()

                val rawIn = BufferedInputStream(s.getInputStream(), 65536)
                val headers = readHeaderLines(rawIn)
                val status = headers.firstOrNull() ?: throw IOException("Empty HTTP response")
                if (!status.contains(" 200 ")) throw IOException("File HTTP error: $status")

                return ChunkCrypto.readEncryptedStream(
                    keyBytes = keyBytes,
                    input = rawIn,
                    output = output,
                    context = ChunkCrypto.CONTEXT_FILE,
                    fileIndex = index,
                    onChunkPlainBytes = onChunkPlainBytes
                )
            }
        } finally {
            cancelHandle.dispose()
        }
    }

    private suspend fun sendDoneAck(
        host: String,
        port: Int,
        token: String,
        bindLocalIp: String?,
        connectTimeoutMs: Int
    ) {
        val sock = openBoundOrFallbackSocket(host, port, bindLocalIp, connectTimeoutMs)
        val cancelHandle = currentCoroutineContext().job.invokeOnCompletion { cause ->
            if (cause != null) runCatching { sock.close() }
        }
        try {
            sock.use { s ->
                s.soTimeout = 5_000
                val req = "POST /done?tok=$token HTTP/1.1\r\nHost: $host:$port\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                s.getOutputStream().write(req.toByteArray(Charsets.US_ASCII))
                s.getOutputStream().flush()
                val rawIn = BufferedInputStream(s.getInputStream(), 1024)
                readHeaderLines(rawIn)
            }
        } finally {
            cancelHandle.dispose()
        }
    }

    private suspend fun openBoundOrFallbackSocket(
        host: String,
        port: Int,
        bindLocalIp: String?,
        connectTimeoutMs: Int
    ): Socket {
        currentCoroutineContext().ensureActive()
        if (!bindLocalIp.isNullOrBlank()) {
            val boundSocket = Socket()
            val cancelHandle = currentCoroutineContext().job.invokeOnCompletion { cause ->
                if (cause != null) runCatching { boundSocket.close() }
            }
            try {
                boundSocket.bind(InetSocketAddress(InetAddress.getByName(bindLocalIp), 0))
                boundSocket.connect(InetSocketAddress(host, port), connectTimeoutMs)
                cancelHandle.dispose()
                return boundSocket
            } catch (e: IOException) {
                cancelHandle.dispose()
                runCatching { boundSocket.close() }
                currentCoroutineContext().ensureActive()
                Log.w(TAG, "Bound connect failed: ${e.message}, retrying unbound")
            }
        }
        val socket = Socket()
        val cancelHandle = currentCoroutineContext().job.invokeOnCompletion { cause ->
            if (cause != null) runCatching { socket.close() }
        }
        try {
            socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
            cancelHandle.dispose()
            return socket
        } catch (e: Exception) {
            cancelHandle.dispose()
            runCatching { socket.close() }
            throw e
        }
    }

    private fun parseContentLength(headers: List<String>): Long {
        for (h in headers.drop(1)) {
            val idx = h.indexOf(':')
            if (idx > 0 && h.substring(0, idx).trim().equals("Content-Length", ignoreCase = true)) {
                return h.substring(idx + 1).trim().toLongOrNull() ?: -1L
            }
        }
        return -1L
    }

    private fun readHeaderLines(input: BufferedInputStream): List<String> {
        val lines = mutableListOf<String>()
        val sb = StringBuilder()
        var totalBytes = 0
        while (true) {
            val b = input.read()
            if (b == -1) break
            totalBytes++
            if (totalBytes > MAX_HEADER_BYTES) {
                throw IOException("HTTP headers exceed size limit ($MAX_HEADER_BYTES bytes)")
            }
            if (b == '\n'.code) {
                val line = sb.toString().trimEnd('\r')
                sb.setLength(0)
                if (line.isEmpty()) break
                if (lines.size >= MAX_HEADER_LINES) {
                    throw IOException("HTTP headers exceed line limit ($MAX_HEADER_LINES lines)")
                }
                lines.add(line)
            } else {
                sb.append(b.toChar())
            }
        }
        return lines
    }
}
