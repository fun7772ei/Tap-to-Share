package fun7772ei.taptoshare.ui.animation

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
import android.transition.ChangeBounds
import android.transition.TransitionManager
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.LinearProgressIndicator
import fun7772ei.taptoshare.R
import fun7772ei.taptoshare.SpringInterpolator
import fun7772ei.taptoshare.UiHelpers

sealed class StageContent {
    data class Media(
        val title: String,
        val subtitle: String,
        val bitmap: Bitmap? = null,
        val isVideo: Boolean = false,
        val count: Int = 1
    ) : StageContent()

    data class Url(
        val domain: String,
        val fullUrl: String
    ) : StageContent()

    data class Text(
        val title: String,
        val preview: String,
        val charCount: Int
    ) : StageContent()

    data class FileBundle(
        val title: String,
        val subtitle: String,
        val fileCount: Int = 1,
        val isFolder: Boolean = false,
        val isBenchmark: Boolean = false
    ) : StageContent()
}

data class StageAction(
    val label: String,
    val onClick: () -> Unit
)

/**
 * Directional transfer stage animation view:
 * - SENDER: Crisp card floats -> swells forward -> shrinks, rounds & blurs inward -> vanishes upward, leaving an empty stage + "Sent" emblem.
 * - RECEIVER: Empty stage + center-outward radial frost -> Hollow translucent crystal ring -> ring bursts outward & received photo materializes/sharpens out of Gaussian blur + top-left status pill.
 */
class TransferStageView(
    context: Context,
    private val isReceiver: Boolean,
    private val onBlurRadiusChanged: (Int) -> Unit = {},
    private val onBackdropTap: () -> Unit
) : FrameLayout(context) {

    private val backdropTapLayer: View
    private val auraView: TransferAuraView
    private val foregroundColumn: LinearLayout
    private val topContainer: LinearLayout
    private val statusPill: DynamicStatusPillView
    private val peerSubtitle: TextView

    private val centerStage: FrameLayout
    private val previewCard: MaterialCardView
    private val previewInner: FrameLayout
    private val previewImage: ImageView
    private val videoBadge: ImageView
    private val mediaFooter: LinearLayout
    private val mediaTitle: TextView
    private val mediaMeta: TextView
    private val richInfoContainer: LinearLayout
    private val richIconBadge: ImageView
    private val richTitle: TextView
    private val richSubtitle: TextView
    private val richCountBadge: TextView

    private val portalRing: PortalRingView
    private val portalCaptionPill: TextView

    private val sentCompleteView: LinearLayout
    private val sentCompleteTitle: TextView
    private val sentCompleteSub: TextView

    private val bottomDock: LinearLayout
    private val dockDetailText: TextView
    private val dockProgress: LinearProgressIndicator
    private val dockTelemetryText: TextView
    private val buttonRow: LinearLayout
    private val primaryButton: MaterialButton
    private val secondaryButton: MaterialButton
    private val closeButton: MaterialButton

    private val lightboxOverlay: FrameLayout
    private val lightboxImage: ImageView
    private val lightboxTitle: TextView

    private var currentBitmap: Bitmap? = null
    private var floatAnimator: ValueAnimator? = null
    private var blurAnimator: ValueAnimator? = null
    private var windowBlurAnimator: ValueAnimator? = null
    private var currentWindowBlur = 0
    private var cardRevealTriggered = false
    private var senderDeparted = false
    private var isLightboxVisible = false

    var onCardTap: (() -> Unit)? = null

    init {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        isClickable = true

        backdropTapLayer = View(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { onBackdropTap() }
        }
        addView(backdropTapLayer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        auraView = TransferAuraView(context).apply {
            setDirection(isReceiver)
        }
        addView(auraView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        foregroundColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        addView(foregroundColumn, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        topContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(UiHelpers.dp(context, 16), UiHelpers.dp(context, 12), UiHelpers.dp(context, 16), UiHelpers.dp(context, 8))
        }
        statusPill = DynamicStatusPillView(context)
        topContainer.addView(statusPill, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        peerSubtitle = TextView(context).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#D4D4D8"))
            gravity = Gravity.CENTER
            visibility = GONE
            setPadding(UiHelpers.dp(context, 2), UiHelpers.dp(context, 6), 0, 0)
        }
        topContainer.addView(peerSubtitle, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        foregroundColumn.addView(topContainer, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // Center Stage
        centerStage = FrameLayout(context).apply {
            clipChildren = false
            clipToPadding = false
        }

        val cardWidth = (context.resources.displayMetrics.widthPixels * 0.82f).toInt().coerceIn(
            UiHelpers.dp(context, 250),
            UiHelpers.dp(context, 360)
        )
        previewCard = MaterialCardView(context).apply {
            radius = UiHelpers.dpF(context, 26f)
            cardElevation = UiHelpers.dpF(context, 20f)
            setCardBackgroundColor(Color.parseColor("#EB1C1C1E"))
            strokeColor = Color.parseColor("#38FFFFFF")
            strokeWidth = UiHelpers.dp(context, 1)
            isClickable = true
            isFocusable = true
            UiHelpers.applyPressScale(this, 0.975f)
        }

        previewInner = FrameLayout(context)
        previewImage = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = GONE
        }
        previewInner.addView(previewImage, LayoutParams(LayoutParams.MATCH_PARENT, UiHelpers.dp(context, 248)))

        videoBadge = ImageView(context).apply {
            setImageDrawable(UiHelpers.tintIcon(context, R.drawable.ic_play_circle, Color.WHITE))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#66000000"))
            }
            val p = UiHelpers.dp(context, 10)
            setPadding(p, p, p, p)
            visibility = GONE
        }
        previewInner.addView(videoBadge, LayoutParams(UiHelpers.dp(context, 52), UiHelpers.dp(context, 52), Gravity.CENTER))

        mediaFooter = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.TRANSPARENT, Color.parseColor("#CC060A14"))
            )
            val hp = UiHelpers.dp(context, 16)
            val vp = UiHelpers.dp(context, 14)
            setPadding(hp, UiHelpers.dp(context, 28), hp, vp)
            visibility = GONE
        }
        mediaTitle = TextView(context).apply {
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        mediaMeta = TextView(context).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#BAE6FD"))
            maxLines = 1
        }
        mediaFooter.addView(mediaTitle)
        mediaFooter.addView(mediaMeta)
        previewInner.addView(mediaFooter, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        richInfoContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val p = UiHelpers.dp(context, 24)
            setPadding(p, p, p, p)
            visibility = GONE
        }
        richIconBadge = ImageView(context).apply {
            val ip = UiHelpers.dp(context, 14)
            setPadding(ip, ip, ip, ip)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#24FFFFFF"))
            }
        }
        richInfoContainer.addView(richIconBadge, LinearLayout.LayoutParams(UiHelpers.dp(context, 60), UiHelpers.dp(context, 60)).apply {
            bottomMargin = UiHelpers.dp(context, 14)
        })

        richTitle = TextView(context).apply {
            textSize = 16.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        richInfoContainer.addView(richTitle, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = UiHelpers.dp(context, 6)
        })

        richSubtitle = TextView(context).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#D4D4D8"))
            gravity = Gravity.CENTER
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
        }
        richInfoContainer.addView(richSubtitle, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        richCountBadge = TextView(context).apply {
            textSize = 11.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            val hp = UiHelpers.dp(context, 10)
            val vp = UiHelpers.dp(context, 4)
            setPadding(hp, vp, hp, vp)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = UiHelpers.dpF(context, 999f)
                setColor(Color.parseColor("#26FFFFFF"))
            }
            visibility = GONE
        }
        richInfoContainer.addView(richCountBadge, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = UiHelpers.dp(context, 12)
        })

        previewInner.addView(richInfoContainer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        previewCard.addView(previewInner)

        previewCard.setOnClickListener {
            val customTap = onCardTap
            if (customTap != null) {
                customTap()
                return@setOnClickListener
            }
            val bmp = currentBitmap
            if (bmp != null && isReceiver && cardRevealTriggered) {
                showImageLightbox(bmp, mediaTitle.text?.toString().orEmpty())
            }
        }
        centerStage.addView(previewCard, LayoutParams(cardWidth, LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        // Hollow translucent crystal portal ring (Receiver)
        portalRing = PortalRingView(context).apply { visibility = GONE }
        val ringSize = UiHelpers.dp(context, 174)
        centerStage.addView(portalRing, LayoutParams(ringSize, ringSize, Gravity.CENTER))

        portalCaptionPill = TextView(context).apply {
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            fontFeatureSettings = "tnum"
            val hp = UiHelpers.dp(context, 14)
            val vp = UiHelpers.dp(context, 6)
            setPadding(hp, vp, hp, vp)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = UiHelpers.dpF(context, 999f)
                setColor(Color.parseColor("#731C1C1E"))
                setStroke(UiHelpers.dp(context, 1), Color.parseColor("#36FFFFFF"))
            }
            visibility = GONE
        }
        centerStage.addView(portalCaptionPill, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
            topMargin = UiHelpers.dp(context, 224)
        })

        // Sender "Departed / Sent" translucent emblem (replaces vanished card!)
        sentCompleteView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = GONE
            alpha = 0f
        }
        val sentCircle = ImageView(context).apply {
            val ip = UiHelpers.dp(context, 22)
            setPadding(ip, ip, ip, ip)
            setImageDrawable(UiHelpers.tintIcon(context, R.drawable.ic_send_up, Color.WHITE))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#33FFFFFF"))
                setStroke(UiHelpers.dp(context, 2), Color.parseColor("#88FFFFFF"))
            }
        }
        sentCompleteView.addView(sentCircle, LinearLayout.LayoutParams(UiHelpers.dp(context, 84), UiHelpers.dp(context, 84)).apply {
            bottomMargin = UiHelpers.dp(context, 14)
        })
        sentCompleteTitle = TextView(context).apply {
            text = "送信しました"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        sentCompleteSub = TextView(context).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#D4D4D8"))
            fontFeatureSettings = "tnum"
            gravity = Gravity.CENTER
            setPadding(0, UiHelpers.dp(context, 4), 0, 0)
        }
        sentCompleteView.addView(sentCompleteTitle, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        sentCompleteView.addView(sentCompleteSub, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        centerStage.addView(sentCompleteView, LayoutParams(UiHelpers.dp(context, 280), LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        foregroundColumn.addView(centerStage, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        // Bottom Frosted Dock
        bottomDock = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = UiHelpers.createGlassDrawable(
                fillColor = Color.parseColor("#DC1C1C1E"),
                strokeColor = Color.parseColor("#2BFFFFFF"),
                radiusPx = UiHelpers.dpF(context, 26f),
                strokeWidthPx = UiHelpers.dp(context, 1)
            )
            val hp = UiHelpers.dp(context, 18)
            val vp = UiHelpers.dp(context, 16)
            setPadding(hp, vp, hp, vp)
            elevation = UiHelpers.dpF(context, 14f)
            isClickable = true
        }

        dockDetailText = TextView(context).apply {
            textSize = 13.5f
            setTextColor(Color.parseColor("#F2F2F2"))
            setLineSpacing(0f, 1.18f)
            visibility = GONE
        }
        bottomDock.addView(dockDetailText, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = UiHelpers.dp(context, 10)
        })

        dockProgress = LinearProgressIndicator(context).apply {
            visibility = GONE
            max = 1000
            trackCornerRadius = UiHelpers.dp(context, 4)
            trackThickness = UiHelpers.dp(context, 4)
            setIndicatorColor(Color.WHITE)
            trackColor = Color.parseColor("#26FFFFFF")
        }
        bottomDock.addView(dockProgress, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = UiHelpers.dp(context, 6)
        })

        dockTelemetryText = TextView(context).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#D4D4D8"))
            visibility = GONE
            fontFeatureSettings = "tnum"
            setPadding(0, 0, 0, UiHelpers.dp(context, 10))
        }
        bottomDock.addView(dockTelemetryText)

        buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        primaryButton = MaterialButton(context).apply {
            visibility = GONE
            cornerRadius = UiHelpers.dp(context, 16)
            setBackgroundColor(Color.parseColor("#F2F2F2"))
            setTextColor(Color.parseColor("#121214"))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            val hp = UiHelpers.dp(context, 10)
            setPadding(hp, paddingTop, hp, paddingBottom)
        }
        buttonRow.addView(primaryButton, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = UiHelpers.dp(context, 8)
        })

        secondaryButton = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            visibility = GONE
            cornerRadius = UiHelpers.dp(context, 16)
            setTextColor(Color.WHITE)
            setStrokeColorResource(android.R.color.white)
            strokeWidth = UiHelpers.dp(context, 1)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            val hp = UiHelpers.dp(context, 10)
            setPadding(hp, paddingTop, hp, paddingBottom)
        }
        buttonRow.addView(secondaryButton, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = UiHelpers.dp(context, 8)
        })

        closeButton = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonTonalStyle).apply {
            text = "キャンセル"
            cornerRadius = UiHelpers.dp(context, 16)
            setBackgroundColor(Color.parseColor("#24FFFFFF"))
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            val hp = UiHelpers.dp(context, 10)
            setPadding(hp, paddingTop, hp, paddingBottom)
        }
        buttonRow.addView(closeButton, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        bottomDock.addView(buttonRow)

        val dockLp = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            val hm = UiHelpers.dp(context, 16)
            setMargins(hm, UiHelpers.dp(context, 8), hm, UiHelpers.dp(context, 14))
        }
        foregroundColumn.addView(bottomDock, dockLp)

        // Full-Screen In-App Image Lightbox
        lightboxOverlay = FrameLayout(context).apply {
            setBackgroundColor(Color.parseColor("#F5000000"))
            visibility = GONE
            alpha = 0f
            isClickable = true
        }
        lightboxImage = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setOnClickListener { dismissLightbox() }
        }
        lightboxOverlay.addView(lightboxImage, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val lbHeader = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val p = UiHelpers.dp(context, 16)
            setPadding(p, UiHelpers.dp(context, 42), p, p)
        }
        lightboxTitle = TextView(context).apply {
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val lbClose = ImageButton(context).apply {
            setImageDrawable(UiHelpers.tintIcon(context, R.drawable.ic_close, Color.WHITE))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#38FFFFFF"))
            }
            setOnClickListener { dismissLightbox() }
        }
        lbHeader.addView(lightboxTitle, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        lbHeader.addView(lbClose, LinearLayout.LayoutParams(UiHelpers.dp(context, 40), UiHelpers.dp(context, 40)))
        lightboxOverlay.addView(lbHeader, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP))
        addView(lightboxOverlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            topContainer.setPadding(
                UiHelpers.dp(context, 16),
                sys.top + UiHelpers.dp(context, 10),
                UiHelpers.dp(context, 16),
                UiHelpers.dp(context, 8)
            )
            foregroundColumn.setPadding(sys.left, 0, sys.right, sys.bottom)
            lbHeader.setPadding(
                sys.left + UiHelpers.dp(context, 16),
                sys.top + UiHelpers.dp(context, 12),
                sys.right + UiHelpers.dp(context, 16),
                UiHelpers.dp(context, 12)
            )
            insets
        }

        if (isReceiver) {
            previewCard.alpha = 0f
            previewCard.scaleX = 0.16f
            previewCard.scaleY = 0.16f
            applyCardBlur(38f)
            cardRevealTriggered = false
        } else {
            cardRevealTriggered = true
        }
    }

    fun setPeerSubtitle(text: String?) {
        if (text.isNullOrBlank()) {
            peerSubtitle.visibility = GONE
        } else {
            peerSubtitle.text = text
            peerSubtitle.visibility = VISIBLE
        }
    }

    fun setContent(content: StageContent) {
        when (content) {
            is StageContent.Media -> {
                richInfoContainer.visibility = GONE
                previewImage.visibility = VISIBLE
                mediaFooter.visibility = VISIBLE
                videoBadge.visibility = if (content.isVideo) VISIBLE else GONE
                mediaTitle.text = content.title
                mediaMeta.text = if (content.count > 1) "${content.count} 件 • ${content.subtitle}" else content.subtitle
                if (content.bitmap != null) {
                    applyPreviewBitmap(content.bitmap)
                } else {
                    previewImage.setImageDrawable(
                        GradientDrawable(
                            GradientDrawable.Orientation.TL_BR,
                            intArrayOf(Color.parseColor("#262629"), Color.parseColor("#1C1C1E"))
                        )
                    )
                }
            }
            is StageContent.Url -> {
                previewImage.visibility = GONE
                mediaFooter.visibility = GONE
                videoBadge.visibility = GONE
                richInfoContainer.visibility = VISIBLE
                richIconBadge.setImageDrawable(UiHelpers.tintIcon(context, R.drawable.ic_text_link, Color.WHITE))
                richTitle.text = content.domain
                richSubtitle.text = content.fullUrl
                richCountBadge.visibility = VISIBLE
                richCountBadge.text = "URL リンク"
            }
            is StageContent.Text -> {
                previewImage.visibility = GONE
                mediaFooter.visibility = GONE
                videoBadge.visibility = GONE
                richInfoContainer.visibility = VISIBLE
                richIconBadge.setImageDrawable(UiHelpers.tintIcon(context, R.drawable.ic_clipboard, Color.WHITE))
                richTitle.text = content.title
                richSubtitle.text = content.preview
                richCountBadge.visibility = VISIBLE
                richCountBadge.text = "${content.charCount} 文字"
            }
            is StageContent.FileBundle -> {
                previewImage.visibility = GONE
                mediaFooter.visibility = GONE
                videoBadge.visibility = GONE
                richInfoContainer.visibility = VISIBLE
                val iconRes = when {
                    content.isFolder -> R.drawable.ic_folder
                    content.isBenchmark -> R.drawable.ic_sparkles
                    else -> R.drawable.ic_file_doc
                }
                richIconBadge.setImageDrawable(UiHelpers.tintIcon(context, iconRes, Color.WHITE))
                richTitle.text = content.title
                richSubtitle.text = content.subtitle
                if (content.fileCount > 1) {
                    richCountBadge.visibility = VISIBLE
                    richCountBadge.text = "${content.fileCount} ファイル"
                } else {
                    richCountBadge.visibility = GONE
                }
            }
        }
    }

    fun updateThumbnail(bitmap: Bitmap) {
        applyPreviewBitmap(bitmap)
    }

    private fun applyPreviewBitmap(bitmap: Bitmap) {
        currentBitmap = bitmap
        previewImage.setImageBitmap(bitmap)
    }

    fun playEntrance() {
        bottomDock.translationY = UiHelpers.dpF(context, 70f)
        bottomDock.alpha = 0f
        bottomDock.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(340L)
            .setInterpolator(SpringInterpolator(0.68f, 1.4f))
            .start()

        statusPill.translationY = -UiHelpers.dpF(context, 20f)
        statusPill.alpha = 0f
        statusPill.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(300L)
            .setInterpolator(DecelerateInterpolator())
            .start()

        if (!isReceiver) {
            // Sender starts with soft center glow + full crisp card floating calmly
            auraView.animateRadialFrost(true, 420L)
            animateWindowBlur(26, 380L)
            previewCard.scaleX = 0.88f
            previewCard.scaleY = 0.88f
            previewCard.alpha = 0f
            previewCard.animate()
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .setDuration(380L)
                .setInterpolator(SpringInterpolator(0.62f, 1.5f))
                .withEndAction { startCardFloat() }
                .start()
        } else {
            // Receiver starts with NO card, expanding center-outward frost & hollow crystal ring
            auraView.animateRadialFrost(true, 480L)
            animateWindowBlur(68, 480L)
            portalRing.setState(RingState.CONNECTING)
            portalRing.scaleX = 0.25f
            portalRing.scaleY = 0.25f
            portalRing.alpha = 0f
            portalRing.animate()
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .setDuration(360L)
                .setInterpolator(SpringInterpolator(0.64f, 1.5f))
                .start()
        }
    }

    fun playExit(onEnd: () -> Unit) {
        stopCardFloat()
        animateWindowBlur(0, 180L)
        animate()
            .alpha(0f)
            .scaleX(0.96f)
            .scaleY(0.96f)
            .setDuration(200L)
            .setInterpolator(DecelerateInterpolator())
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    onEnd()
                }
            })
            .start()
    }

    fun setPhasePreparing(pillText: String, detail: String) {
        resetForNewTransfer()
        val icon = if (isReceiver) R.drawable.ic_nfc_waves else R.drawable.ic_send_up
        setTopGravity(Gravity.CENTER_HORIZONTAL)
        statusPill.setStatus(pillText, PillTone.CONNECTING, iconRes = icon)
        setDockDetail(detail)
        dockProgress.visibility = VISIBLE
        dockProgress.isIndeterminate = true
        dockTelemetryText.visibility = GONE
        if (isReceiver) {
            auraView.animateRadialFrost(true, 420L)
            animateWindowBlur(68, 420L)
            portalRing.setState(RingState.CONNECTING)
            portalCaptionPill.text = "接続準備中..."
            portalCaptionPill.visibility = VISIBLE
        }
    }

    fun setPhaseReady(pillText: String, detail: String, routeTag: String? = "NFC") {
        resetForNewTransfer()
        val icon = if (isReceiver) R.drawable.ic_nfc_waves else R.drawable.ic_send_up
        setTopGravity(Gravity.CENTER_HORIZONTAL)
        statusPill.setStatus(pillText, PillTone.READY, routeTag, iconRes = icon)
        setDockDetail(detail)
        dockProgress.visibility = GONE
        dockTelemetryText.visibility = GONE
        if (!isReceiver) {
            auraView.animateRadialFrost(true, 420L)
            animateWindowBlur(26, 360L)
            auraView.setFlowActive(false)
            previewCard.visibility = VISIBLE
            previewCard.animate().scaleX(1f).scaleY(1f).translationY(0f).alpha(1f).setDuration(260L).start()
            startCardFloat()
        }
    }

    fun onTransferContact(pillText: String, detail: String, routeTag: String? = null) {
        if (isReceiver && cardRevealTriggered) {
            resetForNewTransfer()
        }
        val icon = if (isReceiver) R.drawable.ic_nfc_waves else R.drawable.ic_send_up
        setTopGravity(Gravity.CENTER_HORIZONTAL)
        statusPill.setStatus(pillText, PillTone.CONNECTING, routeTag, iconRes = icon)
        setDockDetail(detail)
        auraView.animateRadialFrost(true, 440L)
        animateWindowBlur(68, 440L)
        auraView.setFlowActive(true, 0.85f)

        if (!isReceiver) {
            // SENDER: Elastic forward swell (1.0 -> 1.13 -> 1.04)
            stopCardFloat()
            previewCard.animate().cancel()
            previewCard.visibility = VISIBLE
            previewCard.animate()
                .scaleX(1.13f)
                .scaleY(1.13f)
                .translationY(-UiHelpers.dpF(context, 10f))
                .setDuration(180L)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    if (!senderDeparted) {
                        previewCard.animate()
                            .scaleX(1.04f)
                            .scaleY(1.04f)
                            .setDuration(140L)
                            .setInterpolator(DecelerateInterpolator())
                            .start()
                    }
                }
                .start()
        } else {
            // RECEIVER: Center hollow crystal ring active, card remains hidden until arrival bloom
            portalRing.setState(RingState.CONNECTING)
            portalCaptionPill.text = "接続中..."
            portalCaptionPill.visibility = VISIBLE
        }
    }

    fun updateProgress(fraction: Float, speedText: String?, fileLabel: String?, routeTag: String? = null) {
        val clamped = fraction.coerceIn(0f, 1f)
        val pct = (clamped * 100f).toInt().coerceIn(0, 100)
        val arrowIcon = if (isReceiver) R.drawable.ic_receive_down else R.drawable.ic_send_up
        val pillLabel = if (!speedText.isNullOrBlank()) {
            "${if (isReceiver) "受信中" else "送信中"} $pct% • $speedText"
        } else {
            "${if (isReceiver) "受信中" else "送信中"} $pct%"
        }
        statusPill.setStatus(pillLabel, PillTone.TRANSFERRING, routeTag, iconRes = arrowIcon, animateLayout = false)

        dockProgress.visibility = VISIBLE
        dockProgress.isIndeterminate = clamped <= 0f
        if (clamped > 0f) {
            dockProgress.setProgressCompat((clamped * 1000f).toInt().coerceIn(0, 1000), true)
        }
        if (!fileLabel.isNullOrBlank()) {
            dockTelemetryText.visibility = VISIBLE
            dockTelemetryText.text = fileLabel
        }

        if (!isReceiver) {
            // SENDER: Card visibly shrinks, rounds into an orb, lifts slightly, and blurs inward!
            val s = 1.04f - 0.54f * clamped
            previewCard.scaleX = s
            previewCard.scaleY = s
            previewCard.translationY = -UiHelpers.dpF(context, 36f) * clamped
            previewCard.radius = UiHelpers.dpF(context, 26f + 22f * clamped)
            applyCardBlur(16f * clamped * clamped)
        } else {
            // RECEIVER: Hollow crystal ring sweeps smoothly; clean caption below ring
            portalRing.setProgress(clamped)
            portalCaptionPill.visibility = VISIBLE
            portalCaptionPill.text = if (!speedText.isNullOrBlank()) "$pct% • $speedText" else "$pct%"
        }
    }

    fun onInstantSendComplete(pillText: String, detail: String, routeTag: String? = null, speedSubtitle: String? = null) {
        resetForNewTransfer()
        stopCardFloat()
        senderDeparted = true
        auraView.animateRadialFrost(true, 360L)
        animateWindowBlur(64, 340L)
        auraView.setFlowActive(true, 0.9f)
        statusPill.setStatus("送信中...", PillTone.TRANSFERRING, routeTag, iconRes = R.drawable.ic_send_up)

        // Swell -> Implode & Vanish
        previewCard.animate()
            .scaleX(1.12f)
            .scaleY(1.12f)
            .setDuration(150L)
            .withEndAction {
                previewCard.animate()
                    .scaleX(0.46f)
                    .scaleY(0.46f)
                    .translationY(-UiHelpers.dpF(context, 28f))
                    .setDuration(190L)
                    .withEndAction {
                        finishSenderImplosion(pillText, detail, routeTag, speedSubtitle)
                    }
                    .start()
            }
            .start()
    }

    fun onComplete(pillText: String, detail: String, routeTag: String? = null, speedSubtitle: String? = null) {
        stopCardFloat()
        dockProgress.visibility = VISIBLE
        dockProgress.isIndeterminate = false
        dockProgress.setProgressCompat(1000, true)

        if (!isReceiver) {
            senderDeparted = true
            finishSenderImplosion(pillText, detail, routeTag, speedSubtitle)
        } else {
            cardRevealTriggered = true
            portalCaptionPill.animate().alpha(0f).setDuration(160L).withEndAction {
                portalCaptionPill.visibility = GONE
                portalCaptionPill.alpha = 1f
            }.start()

            // Hollow ring bursts outward into stardust
            portalRing.animateBurstOut()
            auraView.triggerReceiverBloomBurst()

            // Top-left vivid blue pill
            setTopGravity(Gravity.START)
            statusPill.setStatus(pillText, PillTone.COMPLETE, routeTag, iconRes = R.drawable.ic_nfc_waves)
            setDockDetail(detail)

            // Photo materializes OUTWARD from exact center and sharpens out of Gaussian blur (38px -> 0px)
            previewCard.visibility = VISIBLE
            previewCard.translationY = 0f
            previewCard.radius = UiHelpers.dpF(context, 26f)
            previewCard.scaleX = 0.16f
            previewCard.scaleY = 0.16f
            previewCard.alpha = 0f
            applyCardBlur(38f)

            previewCard.animate()
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .setStartDelay(90L)
                .setDuration(520L)
                .setInterpolator(SpringInterpolator(0.58f, 1.48f))
                .start()

            blurAnimator?.cancel()
            blurAnimator = ValueAnimator.ofFloat(38f, 0f).apply {
                startDelay = 90L
                duration = 430L
                interpolator = DecelerateInterpolator(1.3f)
                addUpdateListener { anim ->
                    applyCardBlur(anim.animatedValue as Float)
                }
                start()
            }
        }
    }

    private fun finishSenderImplosion(pillText: String, detail: String, routeTag: String?, speedSubtitle: String?) {
        auraView.triggerSenderDepartureBurst()
        setTopGravity(Gravity.CENTER_HORIZONTAL)
        statusPill.setStatus(pillText, PillTone.COMPLETE, routeTag, iconRes = R.drawable.ic_check_circle)
        setDockDetail(detail)

        blurAnimator?.cancel()
        blurAnimator = ValueAnimator.ofFloat(16f, 36f).apply {
            duration = 280L
            addUpdateListener { applyCardBlur(it.animatedValue as Float) }
            start()
        }

        previewCard.animate()
            .scaleX(0.08f)
            .scaleY(0.08f)
            .translationY(-UiHelpers.dpF(context, 108f))
            .alpha(0f)
            .setDuration(300L)
            .setInterpolator(AccelerateInterpolator(1.4f))
            .withEndAction {
                previewCard.visibility = INVISIBLE
                applyCardBlur(0f)
            }
            .start()

        sentCompleteSub.text = speedSubtitle ?: routeTag ?: "送信完了"
        sentCompleteSub.visibility = if (sentCompleteSub.text.isNullOrBlank()) GONE else VISIBLE
        sentCompleteView.visibility = VISIBLE
        sentCompleteView.alpha = 0f
        sentCompleteView.scaleX = 0.72f
        sentCompleteView.scaleY = 0.72f
        sentCompleteView.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setStartDelay(180L)
            .setDuration(360L)
            .setInterpolator(SpringInterpolator(0.62f, 1.45f))
            .start()
    }

    fun showError(pillText: String, message: String) {
        stopCardFloat()
        auraView.setErrorState()
        portalRing.setState(RingState.ERROR)
        portalCaptionPill.visibility = GONE
        statusPill.setStatus(pillText, PillTone.ERROR)
        setDockDetail(message)
        dockProgress.visibility = GONE
        dockTelemetryText.visibility = GONE
    }

    private fun setDockDetail(detail: String) {
        if (detail.isBlank()) {
            dockDetailText.text = ""
            dockDetailText.visibility = GONE
        } else {
            dockDetailText.text = detail
            dockDetailText.visibility = VISIBLE
        }
    }

    fun setTelemetryText(text: String?) {
        if (text.isNullOrBlank()) {
            dockTelemetryText.visibility = GONE
        } else {
            dockTelemetryText.text = text
            dockTelemetryText.visibility = VISIBLE
        }
    }

    fun configureActions(
        primary: StageAction? = null,
        secondary: StageAction? = null,
        closeText: String = "閉じる",
        onClose: () -> Unit
    ) {
        if (primary != null) {
            primaryButton.visibility = VISIBLE
            primaryButton.text = primary.label
            primaryButton.setOnClickListener { primary.onClick() }
        } else {
            primaryButton.visibility = GONE
        }

        if (secondary != null) {
            secondaryButton.visibility = VISIBLE
            secondaryButton.text = secondary.label
            secondaryButton.setOnClickListener { secondary.onClick() }
        } else {
            secondaryButton.visibility = GONE
        }

        closeButton.text = closeText
        closeButton.setOnClickListener { onClose() }
    }

    fun showImageLightbox(bitmap: Bitmap, title: String) {
        isLightboxVisible = true
        lightboxImage.setImageBitmap(bitmap)
        lightboxTitle.text = title
        lightboxOverlay.visibility = VISIBLE
        lightboxOverlay.alpha = 0f
        lightboxImage.scaleX = 0.86f
        lightboxImage.scaleY = 0.86f
        lightboxOverlay.animate().alpha(1f).setDuration(220L).start()
        lightboxImage.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(300L)
            .setInterpolator(SpringInterpolator(0.65f, 1.4f))
            .start()
    }

    fun dismissLightbox(): Boolean {
        if (!isLightboxVisible) return false
        isLightboxVisible = false
        lightboxOverlay.animate()
            .alpha(0f)
            .setDuration(180L)
            .withEndAction { lightboxOverlay.visibility = GONE }
            .start()
        return true
    }

    private fun resetForNewTransfer() {
        val needsReset = cardRevealTriggered || senderDeparted
        if (!needsReset && isReceiver) return

        blurAnimator?.cancel()
        stopCardFloat()
        previewCard.animate().cancel()
        applyCardBlur(0f)
        previewCard.radius = UiHelpers.dpF(context, 26f)
        previewCard.translationY = 0f

        sentCompleteView.animate().cancel()
        sentCompleteView.visibility = GONE
        sentCompleteView.alpha = 0f
        senderDeparted = false

        if (isReceiver) {
            cardRevealTriggered = false
            previewCard.alpha = 0f
            previewCard.scaleX = 0.16f
            previewCard.scaleY = 0.16f
            applyCardBlur(38f)
            portalRing.setState(RingState.CONNECTING)
        } else {
            cardRevealTriggered = true
            previewCard.visibility = VISIBLE
            previewCard.alpha = 1f
            previewCard.scaleX = 1f
            previewCard.scaleY = 1f
        }
    }

    private fun setTopGravity(horizontalGravity: Int) {
        if (topContainer.gravity != horizontalGravity) {
            runCatching {
                if (topContainer.isLaidOut) {
                    TransitionManager.beginDelayedTransition(topContainer, ChangeBounds().setDuration(260L))
                }
            }
            topContainer.gravity = horizontalGravity
            peerSubtitle.gravity = horizontalGravity
        }
    }

    private fun applyCardBlur(radiusPx: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (radiusPx <= 0.6f) {
                previewCard.setRenderEffect(null)
            } else {
                previewCard.setRenderEffect(
                    RenderEffect.createBlurEffect(radiusPx, radiusPx, Shader.TileMode.CLAMP)
                )
            }
        }
    }

    private fun animateWindowBlur(targetPx: Int, durationMs: Long) {
        if (currentWindowBlur == targetPx) return
        windowBlurAnimator?.cancel()
        var lastDispatched = currentWindowBlur
        windowBlurAnimator = ValueAnimator.ofInt(currentWindowBlur, targetPx).apply {
            duration = durationMs
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val v = anim.animatedValue as Int
                currentWindowBlur = v
                if (v == targetPx || kotlin.math.abs(v - lastDispatched) >= 14) {
                    lastDispatched = v
                    onBlurRadiusChanged(v)
                }
            }
            start()
        }
    }

    private fun startCardFloat() {
        if (floatAnimator != null || senderDeparted) return
        floatAnimator = ValueAnimator.ofFloat(-1f, 1f).apply {
            duration = 2500L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val v = anim.animatedValue as Float
                previewCard.translationY = v * UiHelpers.dpF(context, 5.5f)
            }
            start()
        }
    }

    private fun stopCardFloat() {
        floatAnimator?.cancel()
        floatAnimator = null
    }

    override fun onDetachedFromWindow() {
        stopCardFloat()
        blurAnimator?.cancel()
        windowBlurAnimator?.cancel()
        super.onDetachedFromWindow()
    }
}
