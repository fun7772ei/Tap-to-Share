package fun7772ei.taptoshare

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class TransferProtocolTest {

    @Test
    fun privateIpv4AndPortValidation() {
        assertTrue(NetworkAddressValidator.isPrivateIpv4("10.0.0.1"))
        assertTrue(NetworkAddressValidator.isPrivateIpv4("172.16.0.1"))
        assertTrue(NetworkAddressValidator.isPrivateIpv4("172.31.255.254"))
        assertTrue(NetworkAddressValidator.isPrivateIpv4("192.168.1.10"))
        assertTrue(NetworkAddressValidator.isPrivateIpv4("192.168.49.1"))

        assertFalse(NetworkAddressValidator.isPrivateIpv4("8.8.8.8"))
        assertFalse(NetworkAddressValidator.isPrivateIpv4("172.15.0.1"))
        assertFalse(NetworkAddressValidator.isPrivateIpv4("172.32.0.1"))
        assertFalse(NetworkAddressValidator.isPrivateIpv4("127.0.0.1"))
        assertFalse(NetworkAddressValidator.isPrivateIpv4("169.254.1.1"))
        assertFalse(NetworkAddressValidator.isPrivateIpv4("example.com"))
        assertFalse(NetworkAddressValidator.isPrivateIpv4("192.168.1.999"))
        assertFalse(NetworkAddressValidator.isPrivateIpv4(null))

        assertTrue(NetworkAddressValidator.isValidPort(1))
        assertTrue(NetworkAddressValidator.isValidPort(65535))
        assertFalse(NetworkAddressValidator.isValidPort(0))
        assertFalse(NetworkAddressValidator.isValidPort(-1))
        assertFalse(NetworkAddressValidator.isValidPort(65536))
    }

    @Test
    fun tapPayloadV2JsonRoundTrip() {
        val keyB64 = ChunkCrypto.encodeKeyBase64Url(ChunkCrypto.generateKeyBytes())
        val original = TapPayload(
            v = 2,
            t = "file",
            s = "Android Device",
            cnt = 3,
            total = 12345678L,
            preview = "photo1.jpg",
            tok = "abcd1234ef567890",
            key = keyB64,
            lanIp = "192.168.1.10",
            port = 41234,
            p2pSsid = "DIRECT-XY-TapShare",
            p2pPass = "Pass123456",
            p2pIp = "192.168.49.1",
            p2pFreq = 5180,
            forceP2p = true
        )

        val parsed = TapPayload.fromJson(original.toJson())
        assertEquals(original, parsed)
    }

    @Test
    fun multiFileEncryptedBatchRoundTripAndDoneAck() = runBlocking {
        val keyBytes = ChunkCrypto.generateKeyBytes()
        val file1 = "hello-text".toByteArray(Charsets.UTF_8)
        val file2 = ByteArray(150_000) { (it * 13 + 7).toByte() }
        val file3 = ByteArray(0)

        val payloads = listOf(file1, file2, file3)
        val items = listOf(
            SharedItem(name = "note.txt", relativeDir = "MyFolder", size = file1.size.toLong(), mime = "text/plain"),
            SharedItem(name = "photo.bin", relativeDir = "MyFolder/Sub", size = file2.size.toLong(), mime = "application/octet-stream"),
            SharedItem(name = "empty.dat", relativeDir = "", size = 0L, mime = "application/octet-stream")
        )

        val doneSignal = CompletableDeferred<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val server = LocalFileServer(
            items = items,
            token = "tok-unit-123",
            keyBytes = keyBytes,
            isLargeText = false,
            streamOpener = { item ->
                val idx = items.indexOf(item)
                ByteArrayInputStream(payloads[idx])
            },
            onClientDone = { remote -> doneSignal.complete(remote) }
        )

        val port = server.start(scope)
        try {
            val receivedBuffers = mutableListOf<Pair<ManifestItem, ByteArray>>()
            val result = LocalFileClient.downloadBatch(
                host = "127.0.0.1",
                port = port,
                token = "tok-unit-123",
                keyBytes = keyBytes,
                bindLocalIp = null,
                routeName = "LAN",
                connectTimeoutMs = 3000
            ) { manifestItem, writer ->
                val baos = ByteArrayOutputStream()
                val n = writer(baos)
                receivedBuffers.add(manifestItem to baos.toByteArray())
                n to manifestItem.name
            }

            assertEquals("LAN", result.route)
            assertEquals(3, result.manifest.items.size)
            assertEquals("MyFolder", receivedBuffers[0].first.relativeDir)
            assertEquals("MyFolder/Sub", receivedBuffers[1].first.relativeDir)
            assertArrayEquals(file1, receivedBuffers[0].second)
            assertArrayEquals(file2, receivedBuffers[1].second)
            assertArrayEquals(file3, receivedBuffers[2].second)

            val remoteAck = withTimeout(3000) { doneSignal.await() }
            assertTrue(remoteAck.isNotBlank())
        } finally {
            server.stop()
            scope.cancel()
        }
    }
}
