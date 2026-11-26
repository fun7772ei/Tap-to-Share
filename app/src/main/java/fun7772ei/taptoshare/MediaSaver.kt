package fun7772ei.taptoshare

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SavedFile(
    val name: String,
    val relativeDir: String,
    val mime: String,
    val size: Long,
    val uri: Uri,
    val displayPath: String
)

private data class PendingSave(
    val fileName: String,
    val relativeDir: String,
    val mime: String,
    val uri: Uri,
    val displayPath: String,
    val stream: BufferedOutputStream,
    val commit: () -> Unit,
    val rollback: () -> Unit
)

object MediaSaver {
    private const val TAG = "TapToShare"

    fun saveTextFile(context: Context, settings: AppSettings, text: String): SavedFile {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val name = "Shared-text-$stamp.txt"
        val bytes = text.toByteArray(Charsets.UTF_8)
        return executeSave(openTargetOutput(context, settings, name, "", "text/plain")) { out ->
            out.write(bytes)
            bytes.size.toLong()
        }
    }

    suspend fun saveStream(
        context: Context,
        settings: AppSettings,
        name: String,
        relativeDir: String,
        mime: String,
        writer: suspend (OutputStream) -> Long
    ): SavedFile {
        return executeSave(openTargetOutput(context, settings, name, relativeDir, mime)) { out ->
            writer(out)
        }
    }

    private inline fun executeSave(pending: PendingSave, block: (OutputStream) -> Long): SavedFile {
        var bytesWritten = 0L
        try {
            pending.stream.use { bos ->
                bytesWritten = block(bos)
                bos.flush()
            }
            pending.commit()
        } catch (e: Exception) {
            pending.rollback()
            throw e
        }
        return SavedFile(
            name = pending.fileName,
            relativeDir = pending.relativeDir,
            mime = pending.mime,
            size = bytesWritten,
            uri = pending.uri,
            displayPath = pending.displayPath
        )
    }

    private fun openTargetOutput(
        context: Context,
        settings: AppSettings,
        name: String,
        relativeDir: String,
        mime: String
    ): PendingSave {
        val safeName = sanitizeFileName(name)
        val safeRelDir = sanitizeRelativeDir(relativeDir)
        val effectiveMime = mime.ifBlank { "application/octet-stream" }

        val customTreeUri = settings.getCustomTreeUri()
        if (customTreeUri != null) {
            val safPending = runCatching {
                openCustomSafTree(context, customTreeUri, safeName, safeRelDir, effectiveMime)
            }
            if (safPending.isSuccess) {
                return safPending.getOrThrow()
            }
            Log.w(TAG, "SAF tree open failed, falling back to Download/: ${safPending.exceptionOrNull()?.message}")
        }

        return openDownloadsMediaStore(context, safeName, safeRelDir, effectiveMime)
    }

    private fun openCustomSafTree(
        context: Context,
        treeUri: Uri,
        fileName: String,
        relativeDir: String,
        mime: String
    ): PendingSave {
        val cr = context.contentResolver
        val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
        var parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId)

        if (relativeDir.isNotEmpty()) {
            for (segment in relativeDir.split('/')) {
                if (segment.isEmpty()) continue
                parentUri = findOrCreateSubdir(context, treeUri, parentUri, segment)
            }
        }

        val docUri = DocumentsContract.createDocument(cr, parentUri, mime, fileName)
            ?: throw IOException("DocumentsContract.createDocument returned null for $fileName")

        val os = try {
            cr.openOutputStream(docUri, "w") ?: throw IOException("Unable to open SAF output stream")
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(cr, docUri) }
            throw e
        }

        val rootLabel = treeDocId.substringAfter(':', treeDocId).trim('/')
        val prefix = if (rootLabel.isEmpty()) "選択フォルダ" else rootLabel
        val display = if (relativeDir.isEmpty()) "$prefix/$fileName" else "$prefix/$relativeDir/$fileName"
        return PendingSave(
            fileName = fileName,
            relativeDir = relativeDir,
            mime = mime,
            uri = docUri,
            displayPath = display,
            stream = BufferedOutputStream(os, 65536),
            commit = {},
            rollback = { runCatching { DocumentsContract.deleteDocument(cr, docUri) } }
        )
    }

    private fun findOrCreateSubdir(
        context: Context,
        treeUri: Uri,
        parentUri: Uri,
        dirName: String
    ): Uri {
        val cr = context.contentResolver
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            DocumentsContract.getDocumentId(parentUri)
        )
        cr.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            ),
            null,
            null,
            null
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (cursor.moveToNext()) {
                val cName = if (nameIdx >= 0) cursor.getString(nameIdx) else null
                val cMime = if (mimeIdx >= 0) cursor.getString(mimeIdx) else null
                if (cName == dirName && cMime == DocumentsContract.Document.MIME_TYPE_DIR && idIdx >= 0) {
                    return DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(idIdx))
                }
            }
        }

        return DocumentsContract.createDocument(
            cr,
            parentUri,
            DocumentsContract.Document.MIME_TYPE_DIR,
            dirName
        ) ?: throw IOException("Failed to create subfolder $dirName")
    }

    private fun openDownloadsMediaStore(
        context: Context,
        fileName: String,
        relativeDir: String,
        mime: String
    ): PendingSave {
        val cr = context.contentResolver
        val relPath = if (relativeDir.isEmpty()) {
            Environment.DIRECTORY_DOWNLOADS
        } else {
            "${Environment.DIRECTORY_DOWNLOADS}/$relativeDir"
        }

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, relPath)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore insert failed for $fileName")

        val os = try {
            cr.openOutputStream(uri, "w") ?: throw IOException("Unable to open MediaStore output stream")
        } catch (e: Exception) {
            runCatching { cr.delete(uri, null, null) }
            throw e
        }

        val display = if (relativeDir.isEmpty()) "Download/$fileName" else "Download/$relativeDir/$fileName"
        return PendingSave(
            fileName = fileName,
            relativeDir = relativeDir,
            mime = mime,
            uri = uri,
            displayPath = display,
            stream = BufferedOutputStream(os, 65536),
            commit = {
                val doneValues = ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }
                cr.update(uri, doneValues, null, null)
            },
            rollback = { runCatching { cr.delete(uri, null, null) } }
        )
    }

    fun sanitizeFileName(raw: String): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\').trim()
        val cleaned = base.replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1F]"), "_")
        return cleaned.ifEmpty { "shared_file.bin" }
    }

    fun sanitizeRelativeDir(raw: String): String {
        return raw.replace('\\', '/')
            .split('/')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "." && it != ".." }
            .joinToString("/") { sanitizeFileName(it) }
    }
}
