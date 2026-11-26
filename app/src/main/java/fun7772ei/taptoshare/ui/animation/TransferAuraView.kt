package fun7772ei.taptoshare.ui.animation

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.util.Log
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

class TransferAuraView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        private const val TAG = "TapShareAura"

        // Premultiplied-alpha AGSL optical field:
        // 1. Center-outward expanding radial frosted veil + refractive wavefront
        // 2. Directional concentric flow ripples (-1 inward suction for Sender, +1 outward bloom for Receiver)
        // 3. Departure pinch flash vs Arrival shockwave burst
        private const val AGSL_SOURCE = """
            uniform float2 iResolution;
            uniform float iTime;
            uniform float iFrostRadius;
            uniform float iFrostAlpha;
            uniform float iFlowDir;
            uniform float iFlowAlpha;
            uniform float iDepartureBurst;
            uniform float iArrivalBurst;
            uniform float iErrorState;

            half4 main(float2 fragCoord) {
                float2 safeRes = max(iResolution, float2(1.0));
                float minDim = min(safeRes.x, safeRes.y);
                float2 centerD = (fragCoord - float2(0.5, 0.46) * safeRes) / minDim;
                float distC = length(centerD);

                float3 primaryCol = mix(float3(0.76, 0.82, 0.90), float3(0.92, 0.25, 0.25), iErrorState);
                float3 iceCol = mix(float3(0.95, 0.96, 0.99), float3(1.0, 0.55, 0.50), iErrorState);

                // Center-outward radial frosted veil
                float frostMask = 1.0 - smoothstep(max(0.0, iFrostRadius - 0.34), iFrostRadius + 0.04, distC);
                float frostEdge = exp(-pow(distC - iFrostRadius, 2.0) * 90.0) * sin(clamp(iFrostRadius / 1.65, 0.0, 1.0) * 3.14159);

                float breath = 0.90 + 0.10 * sin(iTime * 2.2);
                float centerGlow = exp(-distC * distC * 3.4) * 0.34 * breath;

                float3 col = float3(0.04, 0.042, 0.048) * frostMask * 0.72
                           + primaryCol * (centerGlow * iFrostAlpha)
                           + iceCol * (frostEdge * 0.65 * iFrostAlpha);
                float alpha = clamp(frostMask * 0.62 * iFrostAlpha + centerGlow * 0.32 * iFrostAlpha + frostEdge * 0.42 * iFrostAlpha, 0.0, 0.90);

                // Directional flow ripples (Sender pulls INWARD, Receiver radiates OUTWARD)
                if (iFlowAlpha > 0.005) {
                    float p1 = fract(iTime * 0.78);
                    float p2 = fract(iTime * 0.78 + 0.5);
                    float r1 = (iFlowDir < 0.0) ? (1.0 - p1) * 0.72 : p1 * 0.72;
                    float r2 = (iFlowDir < 0.0) ? (1.0 - p2) * 0.72 : p2 * 0.72;
                    float fade1 = sin(p1 * 3.14159);
                    float fade2 = sin(p2 * 3.14159);
                    float ring1 = exp(-pow(distC - r1, 2.0) * 145.0) * fade1;
                    float ring2 = exp(-pow(distC - r2, 2.0) * 145.0) * fade2;
                    float ripples = (ring1 + ring2) * iFlowAlpha;
                    col += mix(primaryCol, iceCol, 0.72) * ripples * 0.85;
                    alpha = clamp(alpha + ripples * 0.44, 0.0, 0.92);
                }

                // Sender departure: concentrated luminous pinch orb fading upward
                if (iDepartureBurst > 0.001 && iDepartureBurst < 0.999) {
                    float pinchFade = 1.0 - iDepartureBurst;
                    float orb = exp(-distC * distC * (16.0 + 38.0 * iDepartureBurst)) * pinchFade;
                    col += float3(0.96, 0.97, 1.0) * orb * 1.35;
                    alpha = clamp(alpha + orb * 0.70, 0.0, 0.96);
                }

                // Receiver arrival: crisp luminous shockwave blooming radially outward
                if (iArrivalBurst > 0.001 && iArrivalBurst < 0.999) {
                    float bloomFade = 1.0 - iArrivalBurst;
                    float bR = 0.11 + iArrivalBurst * 0.95;
                    float bRing = exp(-pow(distC - bR, 2.0) * 210.0) * bloomFade;
                    float coreFlash = exp(-distC * distC * 8.0) * bloomFade * 0.44;
                    col += float3(0.96, 0.97, 1.0) * bRing * 1.30 + primaryCol * coreFlash;
                    alpha = clamp(alpha + bRing * 0.74 + coreFlash * 0.44, 0.0, 0.96);
                }

                alpha = clamp(alpha, 0.0, 1.0);
                col = clamp(col, 0.0, 1.5);
                return half4(half3(col * alpha), half(alpha));
            }
        """
    }

    private data class Sparkle(
        val angleRad: Float,
        val speed: Float,
        val radiusPx: Float,
        val xOffset: Float
    )

    private var runtimeShader: Any? = null
    private var shaderFailed = false
    private val shaderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sparklePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F4F4F5") }
    private val fallbackMatrix = Matrix()
    private var cachedFallbackNormalShader: RadialGradient? = null
    private var cachedFallbackErrorShader: RadialGradient? = null
    private var cachedMinDim = 1f

    private val sparkles = List(26) {
        Sparkle(
            angleRad = (it * (Math.PI * 2.0 / 26.0) + Random.nextDouble(-0.14, 0.14)).toFloat(),
            speed = 0.55f + Random.nextFloat() * 0.70f,
            radiusPx = 2.0f + Random.nextFloat() * 2.8f,
            xOffset = (Random.nextFloat() - 0.5f) * 0.24f
        )
    }

    private var timeSec = 0f
    private var frostRadius = 0f
    private var frostAlpha = 0f
    private var flowDir = 1f // -1f = sender inward, +1f = receiver outward
    private var flowAlpha = 0f
    private var targetFlowAlpha = 0f
    private var departureBurst = 0f
    private var arrivalBurst = 0f
    private var errorState = 0f

    private var timeAnimator: ValueAnimator? = null
    private var frostAnimator: ValueAnimator? = null
    private var burstAnimator: ValueAnimator? = null

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                runtimeShader = RuntimeShader(AGSL_SOURCE)
            }.onFailure { err ->
                shaderFailed = true
                Log.w(TAG, "AGSL unavailable, falling back to Canvas: ${err.message}")
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) {
            cachedFallbackNormalShader = null
            cachedFallbackErrorShader = null
            return
        }
        cachedMinDim = min(w.toFloat(), h.toFloat()).coerceAtLeast(1f)
        cachedFallbackNormalShader = RadialGradient(
            0f, 0f, cachedMinDim,
            intArrayOf(
                Color.argb(135, 18, 18, 20),
                Color.argb(115, 12, 12, 14),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.72f, 1f),
            Shader.TileMode.CLAMP
        )
        cachedFallbackErrorShader = RadialGradient(
            0f, 0f, cachedMinDim,
            intArrayOf(
                Color.argb(135, 45, 12, 16),
                Color.argb(115, 12, 12, 14),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.72f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    fun setDirection(isReceiver: Boolean) {
        flowDir = if (isReceiver) 1f else -1f
        invalidate()
    }

    fun resetState() {
        frostAnimator?.cancel()
        burstAnimator?.cancel()
        frostRadius = 0f
        frostAlpha = 0f
        flowAlpha = 0f
        targetFlowAlpha = 0f
        departureBurst = 0f
        arrivalBurst = 0f
        errorState = 0f
        stopTimeLoop()
        invalidate()
    }

    fun animateRadialFrost(toExpanded: Boolean, durationMs: Long = 500L) {
        frostAnimator?.cancel()
        errorState = 0f
        if (toExpanded) {
            ensureTimeLoopRunning()
            val startR = frostRadius.coerceAtLeast(0.05f)
            val startA = frostAlpha
            frostAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = durationMs
                interpolator = DecelerateInterpolator(1.35f)
                addUpdateListener { anim ->
                    val f = anim.animatedValue as Float
                    frostRadius = startR + (1.68f - startR) * f
                    frostAlpha = startA + (1f - startA) * f
                    invalidate()
                }
                start()
            }
        } else {
            val startR = frostRadius
            val startA = frostAlpha
            frostAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = durationMs
                interpolator = DecelerateInterpolator()
                addUpdateListener { anim ->
                    val f = anim.animatedValue as Float
                    frostRadius = startR * (1f - f)
                    frostAlpha = startA * (1f - f)
                    invalidate()
                }
                start()
            }
        }
    }

    fun setFlowActive(active: Boolean, intensity: Float = 0.85f) {
        targetFlowAlpha = if (active) intensity else 0f
        if (active) {
            ensureTimeLoopRunning()
        }
        invalidate()
    }

    fun triggerSenderDepartureBurst() {
        targetFlowAlpha = 0f
        burstAnimator?.cancel()
        arrivalBurst = 0f
        ensureTimeLoopRunning()
        burstAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 720L
            interpolator = DecelerateInterpolator(1.4f)
            addUpdateListener { anim ->
                departureBurst = anim.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun triggerReceiverBloomBurst() {
        targetFlowAlpha = 0f
        burstAnimator?.cancel()
        departureBurst = 0f
        ensureTimeLoopRunning()
        burstAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 820L
            interpolator = DecelerateInterpolator(1.45f)
            addUpdateListener { anim ->
                arrivalBurst = anim.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun setErrorState() {
        errorState = 1f
        targetFlowAlpha = 0f
        if (frostRadius < 0.8f) {
            animateRadialFrost(true, 320L)
        }
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (needsContinuousLoop()) {
            ensureTimeLoopRunning()
        }
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE) {
            stopTimeLoop()
        } else if (needsContinuousLoop()) {
            ensureTimeLoopRunning()
        }
    }

    override fun onDetachedFromWindow() {
        stopTimeLoop()
        frostAnimator?.cancel()
        frostAnimator = null
        burstAnimator?.cancel()
        burstAnimator = null
        super.onDetachedFromWindow()
    }

    private fun needsContinuousLoop(): Boolean {
        return targetFlowAlpha > 0.005f ||
            flowAlpha > 0.005f ||
            (frostAlpha > 0.01f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !shaderFailed)
    }

    private fun ensureTimeLoopRunning() {
        if (timeAnimator != null || !isAttachedToWindow || visibility != VISIBLE) return
        timeAnimator = ValueAnimator.ofFloat(0f, 1000f).apply {
            duration = 1_000_000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                timeSec = anim.animatedValue as Float
                flowAlpha += (targetFlowAlpha - flowAlpha) * 0.14f
                if (targetFlowAlpha <= 0.005f && flowAlpha < 0.005f) {
                    flowAlpha = 0f
                }
                invalidate()
                if (!needsContinuousLoop() &&
                    frostAnimator?.isRunning != true &&
                    burstAnimator?.isRunning != true
                ) {
                    stopTimeLoop()
                }
            }
            start()
        }
    }

    private fun stopTimeLoop() {
        timeAnimator?.cancel()
        timeAnimator = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val usedShader = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !shaderFailed) {
            val rs = runtimeShader as? RuntimeShader
            if (rs != null) {
                runCatching {
                    rs.setFloatUniform("iResolution", w, h)
                    rs.setFloatUniform("iTime", timeSec)
                    rs.setFloatUniform("iFrostRadius", frostRadius)
                    rs.setFloatUniform("iFrostAlpha", frostAlpha)
                    rs.setFloatUniform("iFlowDir", flowDir)
                    rs.setFloatUniform("iFlowAlpha", flowAlpha)
                    rs.setFloatUniform("iDepartureBurst", departureBurst)
                    rs.setFloatUniform("iArrivalBurst", arrivalBurst)
                    rs.setFloatUniform("iErrorState", errorState)
                    shaderPaint.shader = rs
                    canvas.drawRect(0f, 0f, w, h, shaderPaint)
                    true
                }.getOrElse { err ->
                    shaderFailed = true
                    Log.w(TAG, "AGSL draw fallback: ${err.message}")
                    false
                }
            } else false
        } else false

        if (!usedShader) {
            drawCanvasFallback(canvas, w, h)
        }

        if (departureBurst in 0.02f..0.96f) {
            drawSenderStardust(canvas, w, h)
        } else if (arrivalBurst in 0.02f..0.96f) {
            drawReceiverStardust(canvas, w, h)
        }
    }

    private fun drawCanvasFallback(canvas: Canvas, w: Float, h: Float) {
        val cx = w * 0.5f
        val cy = h * 0.46f
        val minDim = min(w, h)

        if (frostAlpha > 0.01f && frostRadius > 0.01f) {
            val shader = if (errorState > 0.5f) cachedFallbackErrorShader else cachedFallbackNormalShader
            if (shader != null) {
                val r = minDim * frostRadius
                fallbackMatrix.setScale(frostRadius, frostRadius)
                fallbackMatrix.postTranslate(cx, cy)
                shader.setLocalMatrix(fallbackMatrix)
                fallbackPaint.style = Paint.Style.FILL
                fallbackPaint.alpha = (frostAlpha * 255).toInt().coerceIn(0, 255)
                fallbackPaint.shader = shader
                canvas.drawCircle(cx, cy, r, fallbackPaint)
                fallbackPaint.alpha = 255
            }
        }

        if (flowAlpha > 0.01f) {
            fallbackPaint.shader = null
            fallbackPaint.style = Paint.Style.STROKE
            fallbackPaint.strokeWidth = minDim * 0.018f
            val p1 = (timeSec * 0.78f) % 1f
            val r1 = (if (flowDir < 0f) (1f - p1) else p1) * minDim * 0.70f
            fallbackPaint.color = Color.argb((sin(p1 * Math.PI) * flowAlpha * 165).toInt().coerceIn(0, 255), 240, 240, 245)
            canvas.drawCircle(cx, cy, r1, fallbackPaint)
        }

        if (arrivalBurst in 0.01f..0.99f) {
            fallbackPaint.shader = null
            fallbackPaint.style = Paint.Style.STROKE
            fallbackPaint.strokeWidth = minDim * 0.03f
            fallbackPaint.color = Color.argb(((1f - arrivalBurst) * 200).toInt().coerceIn(0, 255), 255, 255, 255)
            canvas.drawCircle(cx, cy, minDim * (0.10f + 0.90f * arrivalBurst), fallbackPaint)
        }
        fallbackPaint.shader = null
        fallbackPaint.style = Paint.Style.FILL
    }

    private fun drawSenderStardust(canvas: Canvas, w: Float, h: Float) {
        val cx = w * 0.5f
        val cy = h * 0.44f
        val fade = (1f - departureBurst).coerceIn(0f, 1f)
        for (s in sparkles) {
            val sx = cx + s.xOffset * w * (1f - departureBurst * 0.4f)
            val sy = cy - departureBurst * h * 0.34f * s.speed
            sparklePaint.alpha = (fade * 215).toInt().coerceIn(0, 255)
            canvas.drawCircle(sx, sy, s.radiusPx * (0.4f + 0.6f * fade), sparklePaint)
        }
    }

    private fun drawReceiverStardust(canvas: Canvas, w: Float, h: Float) {
        val cx = w * 0.5f
        val cy = h * 0.46f
        val maxR = min(w, h) * 0.56f
        val fade = (1f - arrivalBurst).coerceIn(0f, 1f)
        for (s in sparkles) {
            val dist = maxR * (0.14f + arrivalBurst * s.speed)
            val sx = cx + cos(s.angleRad) * dist
            val sy = cy + sin(s.angleRad) * dist
            sparklePaint.alpha = (fade * 220).toInt().coerceIn(0, 255)
            canvas.drawCircle(sx, sy, s.radiusPx * (0.5f + 0.5f * fade), sparklePaint)
        }
    }
}
