package fun7772ei.taptoshare

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppSettingsAndHistoryTest {

    @Test
    fun themeModeRoundTripAndFallback() {
        for (mode in ThemeMode.entries) {
            assertEquals(mode, ThemeMode.fromKey(mode.key))
        }
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromKey(null))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromKey("invalid"))
    }

    @Test
    fun friendlyModelNameFormatsManufacturerAndModel() {
        assertEquals("Google Pixel 9", AppSettings.friendlyModelName("Google", "Pixel 9"))
        assertEquals("Sony Xperia 1", AppSettings.friendlyModelName("Sony", "Sony Xperia 1"))
        assertEquals("Samsung Model X", AppSettings.friendlyModelName("samsung", "Model X"))
        assertEquals("Android Device", AppSettings.friendlyModelName("", ""))
    }

    @Test
    fun historyEntryRoundTripWithTextPayload() {
        val original = HistoryEntry(
            id = "test-id-1",
            timestampMs = 1700000000000L,
            direction = "SEND",
            peerModel = "Android Device",
            category = "URL",
            title = "https://example.com",
            summary = "リンク",
            totalBytes = 19L,
            route = "NFC",
            speedMBps = 0.0,
            uriString = "https://example.com",
            mimeType = "text/plain",
            fileCount = 1,
            textPayload = "https://example.com"
        )
        val parsed = HistoryEntry.fromJson(original.toJson())
        assertEquals(original, parsed)
    }

    @Test
    fun historyEntryBackwardCompatibleWithoutTextPayload() {
        val legacyJson = JSONObject().apply {
            put("id", "legacy-1")
            put("timestampMs", 1690000000000L)
            put("direction", "RECEIVE")
            put("peerModel", "Pixel 9")
            put("category", "IMAGE")
            put("title", "photo.jpg")
            put("summary", "Download/photo.jpg")
            put("totalBytes", 2048000L)
            put("route", "P2P")
            put("speedMBps", 52.3)
            put("fileCount", 1)
        }
        val parsed = HistoryEntry.fromJson(legacyJson)
        assertEquals("legacy-1", parsed.id)
        assertEquals("photo.jpg", parsed.title)
        assertNull(parsed.textPayload)
        assertNull(parsed.uriString)
    }
}
