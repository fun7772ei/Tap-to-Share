package fun7772ei.taptoshare.ui.animation

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import fun7772ei.taptoshare.UiHelpers
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

enum class RingState {
    HIDDEN,
    CONNECTING,
    PROGRESS,
    ERROR
}

/**
 * Hollow luminous translucent crystal ring for transfer progress indication.
 * The center remains completely transparent so the radial-blurred background shines right through.
 */
class PortalRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var ringState: RingState = RingState.HIDDEN
        private set

    private var animatedProgress = 0f
    private var targetProgress = 0f
    private var spinAngle = 0f
    private var pulsePhase = 0f

    private var loopAnimator: ValueAnimator? = null
    private var progressAnimator: ValueAnimator? = null

    private val outerHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = UiHelpers.dpF(context, 18f)
    }
    private val baseCrystalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#66E8F4FF")
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = UiHelpers.dpF(context, 4.2f)
    }
    private val innerRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#38FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = UiHelpers.dpF(context, 1.2f)
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = UiHelpers.dpF(context, 5.2f)
    }
    private val tipGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val needleGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B8FFFFFF")
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = UiHelpers.dpF(context, 3.2f)
    }

    private val arcRect = RectF()
    private val innerRect = RectF()
    private val sweepMatrix = Matrix()
    private val tipMatrix = Matrix()
    private val tipGlowRadius = UiHelpers.dpF(context, 13f)
    private val innerRimInset = UiHelpers.dpF(context, 4f)

    private var cachedCx = 0f
    private var cachedCy = 0f
    private var cachedRingR = 0f
    private var connectingSweepShader: SweepGradient? = null
    private var progressSweepShader: SweepGradient? = null
    private var tipGlowShader: RadialGradient? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) {
            connectingSweepShader = null
            progressSweepShader = null
            tipGlowShader = null
            return
        }
        val wf = w.toFloat()
        val hf = h.toFloat()
        cachedCx = wf * 0.5f
        cachedCy = hf * 0.5f
        cachedRingR = min(wf, hf) * 0.37f

        arcRect.set(cachedCx - cachedRingR, cachedCy - cachedRingR, cachedCx + cachedRingR, cachedCy + cachedRingR)
        innerRect.set(
            cachedCx - cachedRingR + innerRimInset,
            cachedCy - cachedRingR + innerRimInset,
            cachedCx + cachedRingR - innerRimInset,
            cachedCy + cachedRingR - innerRimInset
        )

        connectingSweepShader = SweepGradient(
            cachedCx, cachedCy,
            intArrayOf(
                Color.TRANSPARENT,
                Color.parseColor("#557DD3FC"),
                Color.parseColor("#CCBAE6FD"),
                Color.WHITE
            ),
            floatArrayOf(0.0f, 0.40f, 0.80f, 1.0f)
        )
        progressSweepShader = SweepGradient(
            cachedCx, cachedCy,
            intArrayOf(
                Color.parseColor("#8838BDF8"),
                Color.parseColor("#CCBAE6FD"),
                Color.WHITE,
                Color.parseColor("#8838BDF8")
            ),
            floatArrayOf(0f, 0.55f, 0.94f, 1f)
        ).apply {
            sweepMatrix.setRotate(-90f, cachedCx, cachedCy)
            setLocalMatrix(sweepMatrix)
        }
        tipGlowShader = RadialGradient(
            0f, 0f, tipGlowRadius,
            intArrayOf(Color.WHITE, Color.parseColor("#AA93C5FD"), Color.TRANSPARENT),
            floatArrayOf(0f, 0.42f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    fun setState(newState: RingState) {
        if (ringState == newState && visibility == VISIBLE) return
        ringState = newState
        when (newState) {
            RingState.HIDDEN -> {
                stopLoop()
                visibility = GONE
            }
            RingState.CONNECTING -> {
                animate().cancel()
                visibility = VISIBLE
                scaleX = 1f
                scaleY = 1f
                alpha = 1f
                animatedProgress = 0f
                targetProgress = 0f
                startLoop()
            }
            RingState.PROGRESS -> {
                animate().cancel()
                visibility = VISIBLE
                alpha = 1f
                startLoop()
            }
            RingState.ERROR -> {
                visibility = VISIBLE
                stopLoop()
                invalidate()
            }
        }
    }

    fun setProgress(fraction: Float) {
        val clamped = fraction.coerceIn(0f, 1f)
        if (ringState != RingState.PROGRESS) {
            setState(RingState.PROGRESS)
        }
        val targetScale = 1f + clamped * 0.08f
        scaleX = targetScale
        scaleY = targetScale
        if (clamped != targetProgress) {
            targetProgress = clamped
            progressAnimator?.cancel()
            progressAnimator = ValueAnimator.ofFloat(animatedProgress, targetProgress).apply {
                duration = 65L
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    animatedProgress = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else {
            invalidate()
        }
    }

    fun animateBurstOut(onEnd: () -> Unit = {}) {
        stopLoop()
        progressAnimator?.cancel()
        ringState = RingState.HIDDEN
        animate().cancel()
        animate()
            .scaleX(2.15f)
            .scaleY(2.15f)
            .alpha(0f)
            .setDuration(260L)
            .setInterpolator(DecelerateInterpolator(1.3f))
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    visibility = GONE
                    scaleX = 1f
                    scaleY = 1f
                    alpha = 1f
                    onEnd()
                }
            })
            .start()
    }

    private fun startLoop() {
        if (loopAnimator != null) return
        loopAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1200L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                spinAngle = (spinAngle + 6.8f) % 360f
                pulsePhase = anim.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopLoop() {
        loopAnimator?.cancel()
        loopAnimator = null
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE) {
            stopLoop()
        } else if (ringState == RingState.CONNECTING || ringState == RingState.PROGRESS) {
            startLoop()
        }
    }

    override fun onDetachedFromWindow() {
        stopLoop()
        progressAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0 || ringState == RingState.HIDDEN) return

        val cx = cachedCx
        val cy = cachedCy
        val ringR = cachedRingR

        // Soft glowing ice-blue ambient halo strictly along the ring circumference (center stays hollow)
        val haloAlpha = (44 + 18 * sin(pulsePhase * Math.PI * 2.0)).toInt().coerceIn(20, 72)
        outerHaloPaint.color = if (ringState == RingState.ERROR) {
            Color.argb(64, 248, 113, 113)
        } else {
            Color.argb(haloAlpha, 125, 211, 252)
        }
        canvas.drawOval(arcRect, outerHaloPaint)

        // Base crystal ring
        canvas.drawOval(arcRect, baseCrystalPaint)
        canvas.drawOval(innerRect, innerRimPaint)

        // Optical top light refraction highlight
        if (ringState != RingState.ERROR) {
            canvas.drawLine(
                cx,
                cy - ringR * 0.92f,
                cx,
                cy - ringR * 0.14f,
                needleGlowPaint
            )
        }

        when (ringState) {
            RingState.CONNECTING -> {
                val sweep = connectingSweepShader
                if (sweep != null) {
                    sweepMatrix.setRotate(spinAngle, cx, cy)
                    sweep.setLocalMatrix(sweepMatrix)
                    arcPaint.shader = sweep
                    canvas.drawArc(arcRect, spinAngle + 20f, 240f, false, arcPaint)
                    arcPaint.shader = null
                }

                val headRad = Math.toRadians((spinAngle + 260f).toDouble())
                drawTipGlow(canvas, cx + (ringR * cos(headRad)).toFloat(), cy + (ringR * sin(headRad)).toFloat())
            }
            RingState.PROGRESS -> {
                val sweep = progressSweepShader
                if (sweep != null) {
                    arcPaint.shader = sweep
                    val sweepDeg = 360f * animatedProgress.coerceIn(0.03f, 1f)
                    canvas.drawArc(arcRect, -90f, sweepDeg, false, arcPaint)
                    arcPaint.shader = null

                    val headRad = Math.toRadians((-90f + sweepDeg).toDouble())
                    drawTipGlow(canvas, cx + (ringR * cos(headRad)).toFloat(), cy + (ringR * sin(headRad)).toFloat())
                }
            }
            RingState.ERROR -> {
                arcPaint.shader = null
                arcPaint.color = Color.parseColor("#FB7185")
                canvas.drawOval(arcRect, arcPaint)
            }
            RingState.HIDDEN -> Unit
        }
    }

    private fun drawTipGlow(canvas: Canvas, tx: Float, ty: Float) {
        val shader = tipGlowShader ?: return
        tipMatrix.setTranslate(tx, ty)
        shader.setLocalMatrix(tipMatrix)
        tipGlowPaint.shader = shader
        canvas.drawCircle(tx, ty, tipGlowRadius, tipGlowPaint)
    }
}
