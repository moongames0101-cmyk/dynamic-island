package com.moongames.dynamicisland

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** iPhone-style battery glyph that can animate its fill. */
class BatteryView(ctx: Context) : View(ctx) {
    private var tint = Color.WHITE
    private var shown = 0f
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        stroke.style = Paint.Style.STROKE
        stroke.strokeWidth = 1.3f.dpf
        stroke.color = 0x88FFFFFF.toInt()
    }

    fun set(level: Int, color: Int, animate: Boolean) {
        tint = color
        val target = level.coerceIn(0, 100) / 100f
        if (animate) {
            val a = ValueAnimator.ofFloat(0f, target)
            a.duration = 1100
            a.startDelay = 250
            a.interpolator = DecelerateInterpolator()
            a.addUpdateListener { shown = it.animatedValue as Float; invalidate() }
            a.start()
        } else {
            shown = target
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val nub = 2.4f.dpf
        val body = RectF(1f, 1f, w - nub - 1.5f, h - 1f)
        val r = h * 0.3f
        canvas.drawRoundRect(body, r, r, stroke)
        fill.color = 0x88FFFFFF.toInt()
        canvas.drawRoundRect(RectF(body.right + 1f, h * 0.34f, w, h * 0.66f), 2f, 2f, fill)
        val inset = 2.2f.dpf
        val fw = (body.width() - 2 * inset) * shown
        if (fw > 0f) {
            fill.color = tint
            canvas.drawRoundRect(
                RectF(body.left + inset, body.top + inset, body.left + inset + fw, body.bottom - inset),
                r * 0.55f, r * 0.55f, fill
            )
        }
    }
}

/** Little animated music bars. */
class WaveformView(ctx: Context) : View(ctx) {
    var tint = Color.WHITE
    private var playing = false
    private var phase = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val anim: ValueAnimator = ValueAnimator.ofFloat(0f, 1f)

    init {
        anim.duration = 1000
        anim.repeatCount = ValueAnimator.INFINITE
        anim.interpolator = LinearInterpolator()
        anim.addUpdateListener {
            phase += 0.16f
            invalidate()
        }
    }

    fun setPlaying(p: Boolean) {
        if (p == playing) { invalidate(); return }
        playing = p
        if (p && isAttachedToWindow) anim.start() else anim.cancel()
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (playing) anim.start()
    }

    override fun onDetachedFromWindow() {
        anim.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val n = 5
        val bw = width / (n * 2f - 1f)
        if (bw <= 0f) return
        paint.color = tint
        for (i in 0 until n) {
            val f = if (playing) 0.3f + 0.7f * abs(sin(phase * (0.9f + i * 0.35f) + i * 1.9f)) else 0.22f
            val bh = max(bw, height * f)
            val x = i * 2f * bw
            val top = (height - bh) / 2f
            canvas.drawRoundRect(x, top, x + bw, top + bh, bw / 2f, bw / 2f, paint)
        }
    }
}

/** Circular progress (downloads, uploads). */
class RingView(ctx: Context) : View(ctx) {
    var progress = 0f
        set(v) { field = v; invalidate() }
    var tint = C.BLUE
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        val sw = max(2f, width * 0.14f)
        paint.strokeWidth = sw
        val r = RectF(sw / 2f, sw / 2f, width - sw / 2f, height - sw / 2f)
        paint.color = 0x40FFFFFF
        canvas.drawArc(r, 0f, 360f, false, paint)
        paint.color = tint
        canvas.drawArc(r, -90f, 360f * progress.coerceIn(0f, 1f), false, paint)
    }
}

object Colors {
    /** Picks a bright, saturated colour from album art (like iOS tints the waveform). */
    fun vibrant(b: Bitmap?): Int {
        if (b == null) return Color.WHITE
        return try {
            val src = if (b.config == Bitmap.Config.HARDWARE) b.copy(Bitmap.Config.ARGB_8888, false) else b
            val s = Bitmap.createScaledBitmap(src, 12, 12, true)
            var best = Color.WHITE
            var score = -1f
            val hsv = FloatArray(3)
            for (x in 0 until 12) for (y in 0 until 12) {
                val c = s.getPixel(x, y)
                Color.colorToHSV(c, hsv)
                val sc = hsv[1] * hsv[2]
                if (sc > score) { score = sc; best = c }
            }
            Color.colorToHSV(best, hsv)
            if (hsv[1] < 0.18f) return Color.WHITE
            hsv[1] = min(hsv[1], 0.8f)
            hsv[2] = max(hsv[2], 0.88f)
            Color.HSVToColor(hsv)
        } catch (e: Exception) {
            Color.WHITE
        }
    }
}
