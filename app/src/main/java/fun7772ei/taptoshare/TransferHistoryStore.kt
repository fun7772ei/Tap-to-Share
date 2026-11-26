package fun7772ei.taptoshare

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class HistoryEntry(
    val id: String = UUID.randomUUID().toString(),
    val timestampMs: Long = System.currentTimeMillis(),
    val direction: String, // "SEND" | "RECEIVE"
    val peerModel: String,
    val category: String,  // "URL" | "TEXT" | "IMAGE" | "VIDEO" | "FILE"
    val title: String,
    val summary: String,
    val totalBytes: Long,
    val route: String,     // "NFC" | "LAN" | "P2P"
    val speedMBps: Double = 0.0,
    val uriString: String? = null,
    val mimeType: String? = null,
    val fileCount: Int = 1,
    val textPayload: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("timestampMs", timestampMs)
        put("direction", direction)
        put("peerModel", peerModel)
        put("category", category)
        put("title", title)
        put("summary", summary)
        put("totalBytes", totalBytes)
        put("route", route)
        put("speedMBps", speedMBps)
        if (uriString != null) put("uriString", uriString)
        if (mimeType != null) put("mimeType", mimeType)
        put("fileCount", fileCount)
        if (textPayload != null) put("textPayload", textPayload)
    }

    companion object {
        fun fromJson(obj: JSONObject): HistoryEntry = HistoryEntry(
            id = obj.optString("id", UUID.randomUUID().toString()),
            timestampMs = obj.optLong("timestampMs", 0L),
            direction = obj.optString("direction", "RECEIVE"),
            peerModel = obj.optString("peerModel", "Unknown"),
            category = obj.optString("category", "FILE"),
            title = obj.optString("title", ""),
            summary = obj.optString("summary", ""),
            totalBytes = obj.optLong("totalBytes", 0L),
            route = obj.optString("route", ""),
            speedMBps = obj.optDouble("speedMBps", 0.0),
            uriString = if (obj.has("uriString") && !obj.isNull("uriString")) obj.getString("uriString") else null,
            mimeType = if (obj.has("mimeType") && !obj.isNull("mimeType")) obj.getString("mimeType") else null,
            fileCount = obj.optInt("fileCount", 1),
            textPayload = if (obj.has("textPayload") && !obj.isNull("textPayload")) obj.getString("textPayload") else null
        )
    }
}

class TransferHistoryStore(context: Context) {
    private val file = File(context.applicationContext.filesDir, "history.json")

    fun getAll(): List<HistoryEntry> = synchronized(fileLock) {
        if (!file.exists()) return@synchronized emptyList()
        runCatching {
            val arr = JSONArray(file.readText(Charsets.UTF_8))
            buildList(arr.length()) {
                for (i in 0 until arr.length()) {
                    add(HistoryEntry.fromJson(arr.getJSONObject(i)))
                }
            }
        }.getOrDefault(emptyList())
    }

    fun add(entry: HistoryEntry) = synchronized(fileLock) {
        val current = getAll().toMutableList()
        current.add(0, entry)
        val trimmed = if (current.size > MAX_ENTRIES) current.subList(0, MAX_ENTRIES) else current
        writeAllLocked(trimmed)
    }

    fun deleteById(id: String) = synchronized(fileLock) {
        val remaining = getAll().filterNot { it.id == id }
        if (remaining.isEmpty()) {
            clear()
        } else {
            writeAllLocked(remaining)
        }
    }

    fun clear() = synchronized(fileLock) {
        runCatching { if (file.exists()) file.delete() }
        Unit
    }

    private fun writeAllLocked(entries: List<HistoryEntry>) {
        val arr = JSONArray()
        entries.forEach { arr.put(it.toJson()) }
        val payload = arr.toString()
        runCatching {
            val tmpFile = File(file.parentFile, "${file.name}.tmp")
            tmpFile.writeText(payload, Charsets.UTF_8)
            if (!tmpFile.renameTo(file)) {
                file.writeText(payload, Charsets.UTF_8)
                runCatching { tmpFile.delete() }
            }
        }
    }

    companion object {
        private const val MAX_ENTRIES = 50
        private val fileLock = Any()
    }
}
