package fun7772ei.taptoshare

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log

class TapNdefHceService : HostApduService() {

    private enum class SelectedFile {
        NONE,
        CC,
        NDEF
    }

    private var appSelected = false
    private var selectedFile = SelectedFile.NONE
    private var completedReadInSession = false
    private var sessionNdefBytes: ByteArray? = null

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        if (commandApdu == null || commandApdu.size < 4) {
            return SW_WRONG_LENGTH
        }

        val cla = commandApdu[0].toInt() and 0xFF
        val ins = commandApdu[1].toInt() and 0xFF
        val p1 = commandApdu[2].toInt() and 0xFF
        val p2 = commandApdu[3].toInt() and 0xFF

        if (cla == 0x00 && ins == 0xA4 && p1 == 0x04 && p2 == 0x00) {
            return handleSelectAid(commandApdu)
        }

        if (!appSelected) {
            return SW_FILE_NOT_FOUND
        }

        if (cla == 0x00 && ins == 0xA4 && p1 == 0x00 && (p2 == 0x0C || p2 == 0x00)) {
            return handleSelectFile(commandApdu)
        }

        if (cla == 0x00 && ins == 0xB0) {
            return handleReadBinary(commandApdu, p1, p2)
        }

        return SW_INS_NOT_SUPPORTED
    }

    private fun handleSelectAid(apdu: ByteArray): ByteArray {
        if (apdu.size < 5 + NDEF_AID.size) {
            return SW_FILE_NOT_FOUND
        }
        val lc = apdu[4].toInt() and 0xFF
        if (lc != NDEF_AID.size || apdu.size < 5 + lc) {
            return SW_FILE_NOT_FOUND
        }
        val aidMatches = NDEF_AID.indices.all { idx -> apdu[5 + idx] == NDEF_AID[idx] }
        if (!aidMatches) {
            appSelected = false
            selectedFile = SelectedFile.NONE
            sessionNdefBytes = null
            return SW_FILE_NOT_FOUND
        }

        val payload = NdefPayloadStore.activeNdefBytes
        if (payload == null) {
            appSelected = false
            selectedFile = SelectedFile.NONE
            sessionNdefBytes = null
            Log.d(TAG, "SELECT_AID rejected: no active payload")
            return SW_FILE_NOT_FOUND
        }

        appSelected = true
        selectedFile = SelectedFile.NONE
        completedReadInSession = false
        sessionNdefBytes = payload
        Log.d(TAG, "SELECT_AID ok payloadBytes=${payload.size}")
        return SW_OK
    }

    private fun handleSelectFile(apdu: ByteArray): ByteArray {
        if (apdu.size < 7) {
            return SW_FILE_NOT_FOUND
        }
        val lc = apdu[4].toInt() and 0xFF
        if (lc != 2) {
            return SW_FILE_NOT_FOUND
        }
        val fid1 = apdu[5].toInt() and 0xFF
        val fid2 = apdu[6].toInt() and 0xFF
        return when {
            fid1 == 0xE1 && fid2 == 0x03 -> {
                selectedFile = SelectedFile.CC
                SW_OK
            }
            fid1 == 0xE1 && fid2 == 0x04 -> {
                selectedFile = SelectedFile.NDEF
                SW_OK
            }
            else -> {
                selectedFile = SelectedFile.NONE
                SW_FILE_NOT_FOUND
            }
        }
    }

    private fun handleReadBinary(apdu: ByteArray, p1: Int, p2: Int): ByteArray {
        val offset = (p1 shl 8) or p2
        val leRaw = if (apdu.size >= 5) (apdu[4].toInt() and 0xFF) else 0
        val le = if (leRaw == 0) 256 else leRaw

        val fileBytes: ByteArray = when (selectedFile) {
            SelectedFile.CC -> CC_FILE
            SelectedFile.NDEF -> {
                val ndef = sessionNdefBytes ?: return SW_FILE_NOT_FOUND
                val len = ndef.size
                ByteArray(2 + len).apply {
                    this[0] = ((len ushr 8) and 0xFF).toByte()
                    this[1] = (len and 0xFF).toByte()
                    System.arraycopy(ndef, 0, this, 2, len)
                }
            }
            SelectedFile.NONE -> return SW_FILE_NOT_FOUND
        }

        if (offset >= fileBytes.size) {
            return SW_WRONG_P1P2
        }

        val end = minOf(fileBytes.size, offset + le)
        val slice = fileBytes.copyOfRange(offset, end)
        val response = ByteArray(slice.size + 2)
        System.arraycopy(slice, 0, response, 0, slice.size)
        response[slice.size] = 0x90.toByte()
        response[slice.size + 1] = 0x00.toByte()

        if (selectedFile == SelectedFile.NDEF && !completedReadInSession && fileBytes.size > 2 && end >= fileBytes.size) {
            completedReadInSession = true
            val count = NdefPayloadStore.notifyReadCompleted()
            Log.d(TAG, "NDEF_READ_COMPLETE bytes=${fileBytes.size} count=$count")
        }

        return response
    }

    override fun onDeactivated(reason: Int) {
        val wasCompleted = completedReadInSession
        appSelected = false
        selectedFile = SelectedFile.NONE
        completedReadInSession = false
        sessionNdefBytes = null
        Log.d(TAG, "HCE deactivated reason=$reason completed=$wasCompleted")
        if (wasCompleted) {
            NdefPayloadStore.notifyDeactivatedAfterRead()
        }
    }

    companion object {
        private const val TAG = "TapShareNFC"

        private val NDEF_AID = byteArrayOf(
            0xD2.toByte(), 0x76, 0x00, 0x00, 0x85.toByte(), 0x01, 0x01
        )

        // NFC Forum Type 4 Tag v2.0 Capability Container (read-only, max NDEF 32767 bytes)
        private val CC_FILE = byteArrayOf(
            0x00, 0x0F,
            0x20,
            0x00, 0xFF.toByte(),
            0x00, 0xFF.toByte(),
            0x04, 0x06,
            0xE1.toByte(), 0x04,
            0x7F, 0xFF.toByte(),
            0x00, 0xFF.toByte()
        )

        private val SW_OK = byteArrayOf(0x90.toByte(), 0x00)
        private val SW_FILE_NOT_FOUND = byteArrayOf(0x6A, 0x82.toByte())
        private val SW_WRONG_P1P2 = byteArrayOf(0x6B, 0x00)
        private val SW_WRONG_LENGTH = byteArrayOf(0x67, 0x00)
        private val SW_INS_NOT_SUPPORTED = byteArrayOf(0x6D, 0x00)
    }
}
