package fun7772ei.taptoshare.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ThumbnailLoader {

    private val cache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private var cachedDemoBitmap: Bitmap? = null

    suspend fun loadThumbnail(
        context: Context,
        uri: Uri,
        mime: String = "*/*",
        reqSizePx: Int = 480
    ): Bitmap? {
        val key = "$uri#$reqSizePx"
        cache.get(key)?.let { if (!it.isRecycled) return it }

        return withContext(Dispatchers.IO) {
            val appCtx = context.applicationContext
            val bmp = runCatching {
                appCtx.contentResolver.loadThumbnail(uri, Size(reqSizePx, reqSizePx), null)
            }.getOrNull() ?: runCatching {
                when {
                    mime.startsWith("video/", ignoreCase = true) -> decodeVideoFrame(appCtx, uri, reqSizePx)
                    mime.startsWith("image/", ignoreCase = true) || mime == "*/*" -> decodeSampledImage(appCtx, uri, reqSizePx)
                    else -> null
                }
            }.getOrNull()

            if (bmp != null) {
                cache.put(key, bmp)
            }
            bmp
        }
    }

    private fun decodeSampledImage(context: Context, uri: Uri, reqSize: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var inSampleSize = 1
        while (bounds.outWidth / inSampleSize > reqSize * 2 || bounds.outHeight / inSampleSize > reqSize * 2) {
            inSampleSize *= 2
        }
        val opts = BitmapFactory.Options().apply { this.inSampleSize = inSampleSize }
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
    }

    private fun decodeVideoFrame(context: Context, uri: Uri, reqSize: Int): Bitmap? {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(context, uri)
            mmr.getScaledFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, reqSize, reqSize)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { mmr.release() }
        }
    }

    fun createDemoArtworkBitmap(width: Int = 720, height: Int = 540): Bitmap {
        cachedDemoBitmap?.let { if (!it.isRecycled) return it }
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val w = width.toFloat()
        val h = height.toFloat()

        // Twilight gradient sky
        val skyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, w * 0.2f, h,
                intArrayOf(
                    Color.parseColor("#071329"),
                    Color.parseColor("#0E2954"),
                    Color.parseColor("#1B5299"),
                    Color.parseColor("#2A7BB8")
                ),
                floatArrayOf(0f, 0.35f, 0.72f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, w, h, skyPaint)

        // Luminous aurora ribbon
        val auroraPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                w * 0.62f, h * 0.32f, w * 0.55f,
                intArrayOf(
                    Color.parseColor("#AA38BDF8"),
                    Color.parseColor("#66818CF8"),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(w * 0.62f, h * 0.32f, w * 0.55f, auroraPaint)

        // Glowing crescent / full luminous moon
        val moonHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                w * 0.28f, h * 0.28f, w * 0.22f,
                intArrayOf(Color.parseColor("#99E0F2FE"), Color.TRANSPARENT),
                null,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(w * 0.28f, h * 0.28f, w * 0.22f, moonHalo)
        val moonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F0F9FF") }
        canvas.drawCircle(w * 0.28f, h * 0.28f, w * 0.055f, moonPaint)

        // Stars
        val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val stars = listOf(
            0.14f to 0.14f, 0.44f to 0.12f, 0.76f to 0.15f, 0.86f to 0.26f,
            0.55f to 0.22f, 0.18f to 0.40f, 0.71f to 0.38f, 0.41f to 0.34f
        )
        stars.forEachIndexed { idx, (sx, sy) ->
            starPaint.alpha = if (idx % 2 == 0) 210 else 135
            canvas.drawCircle(w * sx, h * sy, if (idx % 3 == 0) 3.2f else 2.0f, starPaint)
        }

        // Far mountain ridge
        val farRidge = Path().apply {
            moveTo(0f, h * 0.72f)
            lineTo(w * 0.18f, h * 0.56f)
            lineTo(w * 0.38f, h * 0.66f)
            lineTo(w * 0.64f, h * 0.49f)
            lineTo(w * 0.85f, h * 0.63f)
            lineTo(w, h * 0.55f)
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        canvas.drawPath(farRidge, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#132A4A")
        })

        // Foreground ridge
        val nearRidge = Path().apply {
            moveTo(0f, h * 0.80f)
            lineTo(w * 0.24f, h * 0.68f)
            lineTo(w * 0.52f, h * 0.77f)
            lineTo(w * 0.78f, h * 0.64f)
            lineTo(w, h * 0.75f)
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        canvas.drawPath(nearRidge, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0B172A")
        })

        // Lake reflection band
        val lakePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, h * 0.80f, 0f, h,
                intArrayOf(Color.parseColor("#4438BDF8"), Color.parseColor("#FF060E1B")),
                null,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, h * 0.82f, w, h, lakePaint)

        cachedDemoBitmap = bmp
        return bmp
    }
}
