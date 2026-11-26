package fun7772ei.taptoshare

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build

enum class ReceiveCategory {
    URL,
    TEXT,
    IMAGE,
    VIDEO,
    OTHER
}

data class InstalledAppOption(
    val label: String,
    val packageName: String
)

object ReceiveActionHandler {

    fun isSingleUrl(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return false
        if (!trimmed.startsWith("http://", ignoreCase = true) &&
            !trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return false
        }
        val parsed = runCatching { Uri.parse(trimmed) }.getOrNull() ?: return false
        return !parsed.host.isNullOrBlank()
    }

    fun classifyFiles(files: List<SavedFile>): ReceiveCategory {
        if (files.isEmpty()) return ReceiveCategory.OTHER
        return when {
            files.all { it.mime.startsWith("image/", ignoreCase = true) } -> ReceiveCategory.IMAGE
            files.all { it.mime.startsWith("video/", ignoreCase = true) } -> ReceiveCategory.VIDEO
            else -> ReceiveCategory.OTHER
        }
    }

    const val MAX_CLIPBOARD_TEXT_BYTES = 256 * 1024

    fun applyUrlActions(context: Context, settings: AppSettings, url: String): Pair<Boolean, Boolean> {
        val trimmed = url.trim()
        var copied = false
        var opened = false
        if (settings.urlAutoCopy) {
            copied = copyTextToClipboard(context, "Tap to Share URL", trimmed)
        }
        if (settings.urlAutoOpen) {
            opened = openUrl(context, settings, trimmed)
        }
        return copied to opened
    }

    fun applyTextActions(context: Context, settings: AppSettings, text: String): Boolean {
        if (settings.textAutoCopy) {
            return copyTextToClipboard(context, "Tap to Share Text", text)
        }
        return false
    }

    fun applyFileActions(
        context: Context,
        settings: AppSettings,
        files: List<SavedFile>,
        category: ReceiveCategory
    ): Pair<Boolean, Boolean> {
        if (files.isEmpty()) return false to false
        var copied = false
        var opened = false

        when (category) {
            ReceiveCategory.IMAGE -> {
                if (settings.imageAutoCopyUri) {
                    copyUriToClipboard(context, "Tap to Share Image", files.first().uri)
                    copied = true
                }
                if (settings.imageAutoOpen) {
                    opened = openSavedFiles(context, settings, files, category)
                }
            }
            ReceiveCategory.VIDEO -> {
                if (settings.videoAutoOpen) {
                    opened = openSavedFiles(context, settings, files, category)
                }
            }
            ReceiveCategory.OTHER -> {
                if (settings.otherAutoOpen) {
                    opened = openSavedFiles(context, settings, files, category)
                }
            }
            ReceiveCategory.URL, ReceiveCategory.TEXT -> Unit
        }
        return copied to opened
    }

    fun openUrl(context: Context, settings: AppSettings, url: String): Boolean {
        val uri = Uri.parse(url.trim())
        return launchWithOptionalPackage(
            context = context,
            baseIntent = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE),
            preferredPackage = settings.browserPackage
        )
    }

    fun openSavedFiles(
        context: Context,
        settings: AppSettings,
        files: List<SavedFile>,
        category: ReceiveCategory = classifyFiles(files)
    ): Boolean {
        if (files.isEmpty()) return false
        val first = files.first()

        return when (category) {
            ReceiveCategory.IMAGE -> {
                val intent = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(first.uri, first.mime.ifBlank { "image/*" })
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                launchWithOptionalPackage(context, intent, settings.imageViewerPackage)
            }
            ReceiveCategory.VIDEO -> {
                val intent = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(first.uri, first.mime.ifBlank { "video/*" })
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                launchWithOptionalPackage(context, intent, settings.videoPlayerPackage)
            }
            ReceiveCategory.OTHER -> {
                if (files.size == 1) {
                    val mime = first.mime.ifBlank { "*/*" }
                    val intent = Intent(Intent.ACTION_VIEW)
                        .setDataAndType(first.uri, mime)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    launchWithOptionalPackage(context, intent, null)
                } else {
                    val downloadsIntent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    val launchedDownloads = runCatching {
                        context.startActivity(downloadsIntent)
                        true
                    }.getOrDefault(false)
                    if (launchedDownloads) {
                        true
                    } else {
                        val fallback = Intent(Intent.ACTION_VIEW)
                            .setDataAndType(first.uri, first.mime.ifBlank { "*/*" })
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        launchWithOptionalPackage(context, fallback, null)
                    }
                }
            }
            ReceiveCategory.URL, ReceiveCategory.TEXT -> false
        }
    }

    fun copyTextToClipboard(context: Context, label: String, text: String): Boolean {
        if (text.toByteArray(Charsets.UTF_8).size > MAX_CLIPBOARD_TEXT_BYTES) {
            return false
        }
        return runCatching {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: return false
            cm.setPrimaryClip(ClipData.newPlainText(label, text))
            true
        }.getOrDefault(false)
    }

    fun copyUriToClipboard(context: Context, label: String, uri: Uri) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newUri(context.contentResolver, label, uri))
    }

    fun queryInstalledBrowsers(context: Context): List<InstalledAppOption> {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        return queryAppsForIntent(context, intent)
    }

    fun queryInstalledImageViewers(context: Context): List<InstalledAppOption> {
        val intent = Intent(Intent.ACTION_VIEW).setType("image/*")
        return queryAppsForIntent(context, intent)
    }

    fun queryInstalledVideoPlayers(context: Context): List<InstalledAppOption> {
        val intent = Intent(Intent.ACTION_VIEW).setType("video/*")
        return queryAppsForIntent(context, intent)
    }

    private fun queryAppsForIntent(context: Context, intent: Intent): List<InstalledAppOption> {
        val pm = context.packageManager
        val list = if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        }
        return list
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == context.packageName) return@mapNotNull null
                val label = info.loadLabel(pm).toString().ifBlank { pkg }
                InstalledAppOption(label, pkg)
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    private fun launchWithOptionalPackage(
        context: Context,
        baseIntent: Intent,
        preferredPackage: String?
    ): Boolean {
        baseIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!preferredPackage.isNullOrBlank()) {
            val targeted = Intent(baseIntent).setPackage(preferredPackage)
            try {
                context.startActivity(targeted)
                return true
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        return try {
            context.startActivity(baseIntent)
            true
        } catch (_: Exception) {
            false
        }
    }
}
