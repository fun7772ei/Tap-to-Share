package fun7772ei.taptoshare

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object ChunkCrypto {
    const val KEY_SIZE_BYTES = 16
    const val BASE_NONCE_SIZE = 8
    const val FULL_IV_SIZE = 12
    const val GCM_TAG_BITS = 128
    const val CHUNK_SIZE = 64 * 1024

    const val CONTEXT_MANIFEST = "manifest"
    const val CONTEXT_FILE = "file"

    private const val FLAG_MORE = 0
    private const val FLAG_FINAL = 1

    private val random = SecureRandom()

    fun generateKeyBytes(): ByteArray = ByteArray(KEY_SIZE_BYTES).also { random.nextBytes(it) }

    fun encodeKeyBase64Url(keyBytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(keyBytes)

    fun decodeKeyBase64Url(encoded: String): ByteArray =
        Base64.getUrlDecoder().decode(encoded)

    fun buildAad(
        context: String,
        fileIndex: Int = -1,
        chunkIndex: Int = 0,
        isFinal: Boolean = true
    ): ByteArray {
        return "$context:$fileIndex:$chunkIndex:${if (isFinal) 1 else 0}".toByteArray(Charsets.UTF_8)
    }

    fun encryptBytes(keyBytes: ByteArray, plain: ByteArray, aad: ByteArray? = null): ByteArray {
        val iv = ByteArray(FULL_IV_SIZE).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        if (aad != null && aad.isNotEmpty()) {
            cipher.updateAAD(aad)
        }
        val encrypted = cipher.doFinal(plain)
        return ByteArray(iv.size + encrypted.size).apply {
            System.arraycopy(iv, 0, this, 0, iv.size)
            System.arraycopy(encrypted, 0, this, iv.size, encrypted.size)
        }
    }

    fun decryptBytes(keyBytes: ByteArray, payload: ByteArray, aad: ByteArray? = null): ByteArray {
        if (payload.size < FULL_IV_SIZE + 16) {
            throw IOException("Encrypted payload too short: ${payload.size}")
        }
        val iv = payload.copyOfRange(0, FULL_IV_SIZE)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        if (aad != null && aad.isNotEmpty()) {
            cipher.updateAAD(aad)
        }
        return cipher.doFinal(payload, FULL_IV_SIZE, payload.size - FULL_IV_SIZE)
    }

    fun writeEncryptedStream(
        keyBytes: ByteArray,
        input: InputStream,
        output: OutputStream,
        context: String = CONTEXT_FILE,
        fileIndex: Int = 0,
        onChunkPlainBytes: ((Long) -> Unit)? = null
    ): Long {
        val baseNonce = ByteArray(BASE_NONCE_SIZE).also { random.nextBytes(it) }
        output.write(baseNonce)

        val keySpec = SecretKeySpec(keyBytes, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        var curBuf = ByteArray(CHUNK_SIZE)
        var nextBuf = ByteArray(CHUNK_SIZE)
        val iv = ByteArray(FULL_IV_SIZE)
        System.arraycopy(baseNonce, 0, iv, 0, BASE_NONCE_SIZE)

        var chunkIndex = 0
        var totalPlain = 0L

        var curLen = readNextChunk(input, curBuf)
        if (curLen == 0) {
            // Empty stream: emit a single authenticated final chunk with 0 plaintext bytes
            writeBigEndianInt(iv, BASE_NONCE_SIZE, chunkIndex)
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(buildAad(context, fileIndex, chunkIndex, isFinal = true))
            val encrypted = cipher.doFinal(ByteArray(0))
            output.write(FLAG_FINAL)
            writeBigEndianIntToStream(output, encrypted.size)
            output.write(encrypted)
            output.flush()
            return 0L
        }

        while (true) {
            val nextLen = readNextChunk(input, nextBuf)
            val isFinal = nextLen == 0

            writeBigEndianInt(iv, BASE_NONCE_SIZE, chunkIndex)
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(buildAad(context, fileIndex, chunkIndex, isFinal = isFinal))
            val encrypted = cipher.doFinal(curBuf, 0, curLen)

            output.write(if (isFinal) FLAG_FINAL else FLAG_MORE)
            writeBigEndianIntToStream(output, encrypted.size)
            output.write(encrypted)

            chunkIndex++
            totalPlain += curLen
            onChunkPlainBytes?.invoke(curLen.toLong())

            if (isFinal) break
            val tmp = curBuf
            curBuf = nextBuf
            nextBuf = tmp
            curLen = nextLen
        }

        output.flush()
        return totalPlain
    }

    fun readEncryptedStream(
        keyBytes: ByteArray,
        input: InputStream,
        output: OutputStream,
        context: String = CONTEXT_FILE,
        fileIndex: Int = 0,
        onChunkPlainBytes: ((Long) -> Unit)? = null
    ): Long {
        val baseNonce = readExactly(input, BASE_NONCE_SIZE)
        val keySpec = SecretKeySpec(keyBytes, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(FULL_IV_SIZE)
        System.arraycopy(baseNonce, 0, iv, 0, BASE_NONCE_SIZE)

        val lenBuf = ByteArray(4)
        var chunkIndex = 0
        var totalPlain = 0L

        while (true) {
            val flag = input.read()
            if (flag < 0) {
                throw EOFException("Unexpected EOF before authenticated final chunk")
            }
            if (flag != FLAG_MORE && flag != FLAG_FINAL) {
                throw IOException("Invalid encrypted chunk flag: $flag")
            }
            val isFinal = flag == FLAG_FINAL

            readExactlyInto(input, lenBuf, 4)
            val cipherLen = readBigEndianInt(lenBuf, 0)
            if (cipherLen < 16 || cipherLen > CHUNK_SIZE + 64) {
                throw IOException("Invalid encrypted chunk size: $cipherLen")
            }

            val encrypted = readExactly(input, cipherLen)
            writeBigEndianInt(iv, BASE_NONCE_SIZE, chunkIndex)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(buildAad(context, fileIndex, chunkIndex, isFinal = isFinal))
            val plain = cipher.doFinal(encrypted)
            if (plain.isNotEmpty()) {
                output.write(plain)
                totalPlain += plain.size
                onChunkPlainBytes?.invoke(plain.size.toLong())
            }

            chunkIndex++
            if (isFinal) break
        }

        output.flush()
        return totalPlain
    }

    private fun readNextChunk(input: InputStream, dest: ByteArray): Int {
        var total = 0
        while (total < dest.size) {
            val n = input.read(dest, total, dest.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    private fun readExactly(input: InputStream, len: Int): ByteArray {
        val out = ByteArray(len)
        readExactlyInto(input, out, len)
        return out
    }

    private fun readExactlyInto(input: InputStream, dest: ByteArray, len: Int) {
        var offset = 0
        while (offset < len) {
            val n = input.read(dest, offset, len - offset)
            if (n < 0) {
                throw EOFException("Unexpected EOF while reading $len bytes (got $offset)")
            }
            offset += n
        }
    }

    private fun writeBigEndianInt(dest: ByteArray, offset: Int, value: Int) {
        dest[offset] = ((value ushr 24) and 0xFF).toByte()
        dest[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        dest[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        dest[offset + 3] = (value and 0xFF).toByte()
    }

    private fun writeBigEndianIntToStream(out: OutputStream, value: Int) {
        out.write((value ushr 24) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun readBigEndianInt(src: ByteArray, offset: Int): Int {
        return ((src[offset].toInt() and 0xFF) shl 24) or
            ((src[offset + 1].toInt() and 0xFF) shl 16) or
            ((src[offset + 2].toInt() and 0xFF) shl 8) or
            (src[offset + 3].toInt() and 0xFF)
    }
}
