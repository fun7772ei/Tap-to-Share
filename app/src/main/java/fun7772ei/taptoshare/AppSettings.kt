package fun7772ei.taptoshare

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract

enum class ThemeMode(
    val key: String,
    val displayName: String
) {
    SYSTEM("system", "システム"),
    LIGHT("light", "ライト"),
    DARK("dark", "ダーク");

    companion object {
        fun fromKey(key: String?): ThemeMode =
            entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}

class AppSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("tap_to_share_prefs", Context.MODE_PRIVATE)

    var urlAutoOpen: Boolean
        get() = prefs.getBoolean(KEY_URL_AUTO_OPEN, false)
        set(v) = prefs.edit().putBoolean(KEY_URL_AUTO_OPEN, v).apply()

    var urlAutoCopy: Boolean
        get() = prefs.getBoolean(KEY_URL_AUTO_COPY, true)
        set(v) = prefs.edit().putBoolean(KEY_URL_AUTO_COPY, v).apply()

    var textAutoCopy: Boolean
        get() = prefs.getBoolean(KEY_TEXT_AUTO_COPY, true)
        set(v) = prefs.edit().putBoolean(KEY_TEXT_AUTO_COPY, v).apply()

    var imageAutoOpen: Boolean
        get() = prefs.getBoolean(KEY_IMAGE_AUTO_OPEN, false)
        set(v) = prefs.edit().putBoolean(KEY_IMAGE_AUTO_OPEN, v).apply()

    var imageAutoCopyUri: Boolean
        get() = prefs.getBoolean(KEY_IMAGE_AUTO_COPY_URI, true)
        set(v) = prefs.edit().putBoolean(KEY_IMAGE_AUTO_COPY_URI, v).apply()

    var videoAutoOpen: Boolean
        get() = prefs.getBoolean(KEY_VIDEO_AUTO_OPEN, false)
        set(v) = prefs.edit().putBoolean(KEY_VIDEO_AUTO_OPEN, v).apply()

    var otherAutoOpen: Boolean
        get() = prefs.getBoolean(KEY_OTHER_AUTO_OPEN, false)
        set(v) = prefs.edit().putBoolean(KEY_OTHER_AUTO_OPEN, v).apply()

    var preferLanFirst: Boolean
        get() = prefs.getBoolean(KEY_PREFER_LAN_FIRST, true)
        set(v) = prefs.edit().putBoolean(KEY_PREFER_LAN_FIRST, v).apply()

    var autoCloseAfterSend: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CLOSE_AFTER_SEND, true)
        set(v) = prefs.edit().putBoolean(KEY_AUTO_CLOSE_AFTER_SEND, v).apply()

    var vibrateOnComplete: Boolean
        get() = prefs.getBoolean(KEY_VIBRATE_ON_COMPLETE, true)
        set(v) = prefs.edit().putBoolean(KEY_VIBRATE_ON_COMPLETE, v).apply()

    var browserPackage: String?
        get() = prefs.getString(KEY_BROWSER_PKG, null)?.takeIf { it.isNotBlank() }
        set(v) = prefs.edit().putString(KEY_BROWSER_PKG, v?.takeIf { it.isNotBlank() }).apply()

    var imageViewerPackage: String?
        get() = prefs.getString(KEY_IMAGE_VIEWER_PKG, null)?.takeIf { it.isNotBlank() }
        set(v) = prefs.edit().putString(KEY_IMAGE_VIEWER_PKG, v?.takeIf { it.isNotBlank() }).apply()

    var videoPlayerPackage: String?
        get() = prefs.getString(KEY_VIDEO_PLAYER_PKG, null)?.takeIf { it.isNotBlank() }
        set(v) = prefs.edit().putString(KEY_VIDEO_PLAYER_PKG, v?.takeIf { it.isNotBlank() }).apply()

    var customTreeUriString: String?
        get() = prefs.getString(KEY_CUSTOM_TREE_URI, null)?.takeIf { it.isNotBlank() }
        set(v) = prefs.edit().putString(KEY_CUSTOM_TREE_URI, v?.takeIf { it.isNotBlank() }).apply()

    var themeMode: ThemeMode
        get() = ThemeMode.fromKey(prefs.getString(KEY_THEME_MODE, null))
        set(v) = prefs.edit().putString(KEY_THEME_MODE, v.key).apply()

    var dynamicColorEnabled: Boolean
        get() = prefs.getBoolean(KEY_DYNAMIC_COLOR, false)
        set(v) = prefs.edit().putBoolean(KEY_DYNAMIC_COLOR, v).apply()

    var soundEffectsEnabled: Boolean
        get() = prefs.getBoolean(KEY_SOUND_EFFECTS, false)
        set(v) = prefs.edit().putBoolean(KEY_SOUND_EFFECTS, v).apply()

    var deviceNickname: String?
        get() = prefs.getString(KEY_DEVICE_NICKNAME, null)?.trim()?.takeIf { it.isNotBlank() }
        set(v) = prefs.edit().putString(KEY_DEVICE_NICKNAME, v?.trim()?.takeIf { it.isNotBlank() }).apply()

    var onboardingCompleted: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_COMPLETED, false)
        set(v) = prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETED, v).apply()

    fun defaultModelName(): String = friendlyModelName(Build.MANUFACTURER, Build.MODEL)

    fun effectiveDeviceName(): String {
        deviceNickname?.let { return it }
        return defaultModelName()
    }

    fun getCustomTreeUri(): Uri? = customTreeUriString?.let { runCatching { Uri.parse(it) }.getOrNull() }

    fun getDestinationDisplayName(): String {
        val uri = getCustomTreeUri() ?: return "Download/ (既定)"
        return runCatching {
            val docId = DocumentsContract.getTreeDocumentId(uri)
            val cleaned = docId.substringAfter(':', docId).trim('/')
            if (cleaned.isEmpty()) "選択フォルダ/" else "$cleaned/"
        }.getOrDefault("選択フォルダ/")
    }

    companion object {
        private const val KEY_URL_AUTO_OPEN = "url_auto_open"
        private const val KEY_URL_AUTO_COPY = "url_auto_copy"
        private const val KEY_TEXT_AUTO_COPY = "text_auto_copy"
        private const val KEY_IMAGE_AUTO_OPEN = "image_auto_open"
        private const val KEY_IMAGE_AUTO_COPY_URI = "image_auto_copy_uri"
        private const val KEY_VIDEO_AUTO_OPEN = "video_auto_open"
        private const val KEY_OTHER_AUTO_OPEN = "other_auto_open"
        private const val KEY_PREFER_LAN_FIRST = "prefer_lan_first"
        private const val KEY_AUTO_CLOSE_AFTER_SEND = "auto_close_after_send"
        private const val KEY_VIBRATE_ON_COMPLETE = "vibrate_on_complete"
        private const val KEY_BROWSER_PKG = "browser_pkg"
        private const val KEY_IMAGE_VIEWER_PKG = "image_viewer_pkg"
        private const val KEY_VIDEO_PLAYER_PKG = "video_player_pkg"
        private const val KEY_CUSTOM_TREE_URI = "custom_tree_uri"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_DYNAMIC_COLOR = "dynamic_color_enabled"
        private const val KEY_SOUND_EFFECTS = "sound_effects_enabled"
        private const val KEY_DEVICE_NICKNAME = "device_nickname"
        private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"

        fun friendlyModelName(manufacturer: String?, model: String?): String {
            val m = model.orEmpty().trim()
            val brand = manufacturer.orEmpty().trim()
            return when {
                m.isNotEmpty() && brand.isNotEmpty() && !m.startsWith(brand, ignoreCase = true) ->
                    "${brand.replaceFirstChar { it.uppercase() }} $m"
                m.isNotEmpty() -> m
                brand.isNotEmpty() -> brand.replaceFirstChar { it.uppercase() }
                else -> "Android Device"
            }
        }
    }
}
