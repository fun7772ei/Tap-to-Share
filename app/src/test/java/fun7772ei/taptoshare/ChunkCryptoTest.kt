package fun7772ei.taptoshare

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.crypto.AEADBadTagException

class ChunkCryptoTest {

    @Test
    fun base64UrlKeyRoundTrip() {
        val key = ChunkCrypto.generateKeyBytes()
        assertEquals(ChunkCrypto.KEY_SIZE_BYTES, key.size)
        val encoded = ChunkCrypto.encodeKeyBase64Url(key)
        assertTrue(!encoded.contains("="))
        assertArrayEquals(key, ChunkCrypto.decodeKeyBase64Url(encoded))
    }

    @Test
    fun streamEncryptDecrypt_empty() {
        verifyStreamRoundTrip(ByteArray(0))
    }

    @Test
    fun streamEncryptDecrypt_small() {
        verifyStreamRoundTrip("Hello Tap to Share AES-GCM!".toByteArray(Charsets.UTF_8))
    }

    @Test
    fun streamEncryptDecrypt_exactChunkBoundary() {
        val data = ByteArray(ChunkCrypto.CHUNK_SIZE) { (it * 31).toByte() }
        verifyStreamRoundTrip(data)
    }

    @Test
    fun streamEncryptDecrypt_multiChunk() {
        val data = ByteArray(200 * 1024 + 123) { (it * 17 + 5).toByte() }
        verifyStreamRoundTrip(data)
    }

    @Test
    fun streamDecrypt_wrongKeyFails() {
        val key1 = ChunkCrypto.generateKeyBytes()
        val key2 = ChunkCrypto.generateKeyBytes()
        val enc = ByteArrayOutputStream()
        ChunkCrypto.writeEncryptedStream(key1, ByteArrayInputStream("secret".toByteArray()), enc)

        try {
            ChunkCrypto.readEncryptedStream(key2, ByteArrayInputStream(enc.toByteArray()), ByteArrayOutputStream())
            fail("Expected AEADBadTagException for wrong key")
        } catch (_: AEADBadTagException) {
            // Expected
        }
    }

    @Test
    fun streamDecrypt_tamperedByteFails() {
        val key = ChunkCrypto.generateKeyBytes()
        val enc = ByteArrayOutputStream()
        ChunkCrypto.writeEncryptedStream(key, ByteArrayInputStream("tamper-check-payload".toByteArray()), enc)
        val cipherBytes = enc.toByteArray()
        // Flip a byte inside first chunk ciphertext (after 8B nonce + 1B flag + 4B chunk len)
        cipherBytes[15] = (cipherBytes[15].toInt() xor 0x55).toByte()

        try {
            ChunkCrypto.readEncryptedStream(key, ByteArrayInputStream(cipherBytes), ByteArrayOutputStream())
            fail("Expected AEADBadTagException for tampered ciphertext")
        } catch (_: AEADBadTagException) {
            // Expected
        }
    }

    @Test
    fun streamDecrypt_truncatedFails() {
        val key = ChunkCrypto.generateKeyBytes()
        val data = ByteArray(ChunkCrypto.CHUNK_SIZE + 1024) { (it * 7).toByte() }
        val enc = ByteArrayOutputStream()
        ChunkCrypto.writeEncryptedStream(key, ByteArrayInputStream(data), enc)
        val full = enc.toByteArray()
        // Truncate stream before final chunk completes
        val truncated = full.copyOf(full.size - 16)

        try {
            ChunkCrypto.readEncryptedStream(key, ByteArrayInputStream(truncated), ByteArrayOutputStream())
            fail("Expected EOFException on truncated stream")
        } catch (_: java.io.EOFException) {
            // Expected
        }
    }

    @Test
    fun streamDecrypt_forgedFinalFlagFails() {
        val key = ChunkCrypto.generateKeyBytes()
        val data = ByteArray(ChunkCrypto.CHUNK_SIZE + 512) { (it * 11).toByte() }
        val enc = ByteArrayOutputStream()
        ChunkCrypto.writeEncryptedStream(key, ByteArrayInputStream(data), enc)
        val bytes = enc.toByteArray()
        // Flip first chunk flag from FLAG_MORE (0) to FLAG_FINAL (1) to attempt truncation attack
        bytes[ChunkCrypto.BASE_NONCE_SIZE] = 1

        try {
            ChunkCrypto.readEncryptedStream(key, ByteArrayInputStream(bytes), ByteArrayOutputStream())
            fail("Expected AEADBadTagException when final flag is forged")
        } catch (_: AEADBadTagException) {
            // Expected
        }
    }

    @Test
    fun streamDecrypt_wrongFileIndexFails() {
        val key = ChunkCrypto.generateKeyBytes()
        val enc = ByteArrayOutputStream()
        ChunkCrypto.writeEncryptedStream(
            key,
            ByteArrayInputStream("file-0-data".toByteArray()),
            enc,
            context = ChunkCrypto.CONTEXT_FILE,
            fileIndex = 0
        )

        try {
            ChunkCrypto.readEncryptedStream(
                key,
                ByteArrayInputStream(enc.toByteArray()),
                ByteArrayOutputStream(),
                context = ChunkCrypto.CONTEXT_FILE,
                fileIndex = 1
            )
            fail("Expected AEADBadTagException for mismatched fileIndex")
        } catch (_: AEADBadTagException) {
            // Expected
        }
    }

    @Test
    fun bytesDecrypt_wrongAadFails() {
        val key = ChunkCrypto.generateKeyBytes()
        val enc = ChunkCrypto.encryptBytes(
            key,
            "{\"items\":[]}".toByteArray(Charsets.UTF_8),
            ChunkCrypto.buildAad(ChunkCrypto.CONTEXT_MANIFEST)
        )
        try {
            ChunkCrypto.decryptBytes(
                key,
                enc,
                ChunkCrypto.buildAad(ChunkCrypto.CONTEXT_FILE, fileIndex = 0)
            )
            fail("Expected AEADBadTagException for mismatched AAD context")
        } catch (_: AEADBadTagException) {
            // Expected
        }
    }

    private fun verifyStreamRoundTrip(plain: ByteArray) {
        val key = ChunkCrypto.generateKeyBytes()
        val enc = ByteArrayOutputStream()
        val written = ChunkCrypto.writeEncryptedStream(
            key,
            ByteArrayInputStream(plain),
            enc,
            context = ChunkCrypto.CONTEXT_FILE,
            fileIndex = 0
        )
        assertEquals(plain.size.toLong(), written)

        val dec = ByteArrayOutputStream()
        val read = ChunkCrypto.readEncryptedStream(
            key,
            ByteArrayInputStream(enc.toByteArray()),
            dec,
            context = ChunkCrypto.CONTEXT_FILE,
            fileIndex = 0
        )
        assertEquals(plain.size.toLong(), read)
        assertArrayEquals(plain, dec.toByteArray())
    }
}
