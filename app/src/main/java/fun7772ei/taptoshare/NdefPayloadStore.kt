package fun7772ei.taptoshare

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import java.util.concurrent.atomic.AtomicInteger

object NdefPayloadStore {
    const val MIME_TYPE = "application/vnd.taptoshare.v1"
    const val PACKAGE_NAME = "fun7772ei.taptoshare"

    private val lock = Any()

    @Volatile
    var activeNdefBytes: ByteArray? = null
        private set

    @Volatile
    var activeSummary: String = ""
        private set

    private var payloadOwnerToken: Any? = null

    val readCount = AtomicInteger(0)

    private var callbackOwnerToken: Any? = null
    private var onReadCompleted: ((Int) -> Unit)? = null
    private var onDeactivatedAfterRead: (() -> Unit)? = null

    fun registerCallbacks(
        ownerToken: Any,
        onRead: (Int) -> Unit,
        onDeactivated: () -> Unit
    ) {
        synchronized(lock) {
            callbackOwnerToken = ownerToken
            onReadCompleted = onRead
            onDeactivatedAfterRead = onDeactivated
        }
    }

    fun unregisterCallbacks(ownerToken: Any) {
        synchronized(lock) {
            if (callbackOwnerToken === ownerToken) {
                callbackOwnerToken = null
                onReadCompleted = null
                onDeactivatedAfterRead = null
            }
        }
    }

    fun measureNdefSize(json: String): Int {
        val mimeRecord = NdefRecord.createMime(MIME_TYPE, json.toByteArray(Charsets.UTF_8))
        val aarRecord = NdefRecord.createApplicationRecord(PACKAGE_NAME)
        val message = NdefMessage(arrayOf(mimeRecord, aarRecord))
        return message.toByteArray().size
    }

    fun setJsonPayload(json: String, summary: String, ownerToken: Any? = null) {
        val mimeRecord = NdefRecord.createMime(MIME_TYPE, json.toByteArray(Charsets.UTF_8))
        val aarRecord = NdefRecord.createApplicationRecord(PACKAGE_NAME)
        val message = NdefMessage(arrayOf(mimeRecord, aarRecord))
        synchronized(lock) {
            payloadOwnerToken = ownerToken
            activeNdefBytes = message.toByteArray()
            activeSummary = summary
        }
    }

    fun clear(ownerToken: Any? = null) {
        synchronized(lock) {
            if (ownerToken == null || payloadOwnerToken == null || payloadOwnerToken === ownerToken) {
                payloadOwnerToken = null
                activeNdefBytes = null
                activeSummary = ""
            }
        }
    }

    fun notifyReadCompleted(): Int {
        val count = readCount.incrementAndGet()
        val cb = synchronized(lock) { onReadCompleted }
        cb?.invoke(count)
        return count
    }

    fun notifyDeactivatedAfterRead() {
        val cb = synchronized(lock) { onDeactivatedAfterRead }
        cb?.invoke()
    }
}
