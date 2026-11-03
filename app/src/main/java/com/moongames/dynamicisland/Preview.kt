package com.moongames.dynamicisland

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * A little picture of the top of your phone with the island drawn at its real proportions.
 * It redraws as you move the size and position sliders, so you can see what you're getting.
 */
class IslandPreview(ctx: Context, private val accent: Int) : View(ctx) {
    private val prefs = Prefs.get(ctx)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private var shownW = -1f
    private var shownH = -1f
    private var shownX = 0f
    private var shownY = 0f
    private var wall: Shader? = null
    var busy = false
        set(v) { field = v; invalidate() }

    init {
        text.color = Color.WHITE
        text.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    @android.annotation.SuppressLint("DiscouragedApi", "InternalInsetResource")
    private fun statusBarPx(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 24.dp
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        wall = LinearGradient(
            0f, 0f, w.toFloat(), h.toFloat(),
            intArrayOf(Colors.blend(accent, Color.BLACK, 0.55f), 0xFF14141A.toInt(), 0xFF0B0B0F.toInt()),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val screenW = resources.displayMetrics.widthPixels.toFloat()
        val pad = 14f.dpf
        val scale = (w - 2 * pad) / screenW

        // The phone screen.
        val screen = RectF(pad, pad, w - pad, h + 40f.dpf)
        paint.shader = wall
        canvas.drawRoundRect(screen, 26f.dpf, 26f.dpf, paint)
        paint.shader = null

        // Status bar: time on the left, little battery on the right.
        val sbh = statusBarPx() * scale
        text.textSize = max(9f.dpf, sbh * 0.5f)
        val baseline = screen.top + sbh / 2f + text.textSize * 0.36f
        canvas.drawText("9:41", screen.left + 18f.dpf, baseline, text)
        paint.color = 0xCCFFFFFF.toInt()
        val bw = 14f.dpf
        val bh = 6.5f.dpf
        val bx = screen.right - 18f.dpf - bw
        val by = screen.top + sbh / 2f - bh / 2f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f.dpf
        canvas.drawRoundRect(RectF(bx, by, bx + bw, by + bh), 2f.dpf, 2f.dpf, paint)
        paint.style = Paint.Style.FILL
        canvas.drawRoundRect(RectF(bx + 1.5f.dpf, by + 1.5f.dpf, bx + bw - 3f.dpf, by + bh - 1.5f.dpf), 1f.dpf, 1f.dpf, paint)

        // Camera hole (what the island is hiding).
        val cx = screen.centerX()
        paint.color = 0xFF2A2A2E.toInt()
        canvas.drawCircle(cx, screen.top + sbh / 2f, max(3f.dpf, sbh * 0.36f), paint)

        // Faint app content.
        paint.color = 0x14FFFFFF
        var y = screen.top + sbh + 18f.dpf
        for (i in 0 until 3) {
            canvas.drawRoundRect(RectF(screen.left + 16f.dpf, y, screen.right - 16f.dpf, y + 26f.dpf), 8f.dpf, 8f.dpf, paint)
            y += 36f.dpf
        }

        // The island, at its real size relative to the screen.
        val iw = prefs.getInt(P.WIDTH, AutoSize.widthDp(context)).dp * scale
        val ih = prefs.getInt(P.HEIGHT, AutoSize.heightDp(context)).dp * scale
        val ox = prefs.getInt(P.OFF_X, 0).dp * scale
        val oy = prefs.getInt(P.OFF_Y, 0).dp * scale
        if (shownW < 0f) {
            shownW = iw; shownH = ih; shownX = ox; shownY = oy
        } else {
            // Ease towards the new size so slider moves feel like the real island morphing.
            shownW += (iw - shownW) * 0.35f
            shownH += (ih - shownH) * 0.35f
            shownX += (ox - shownX) * 0.35f
            shownY += (oy - shownY) * 0.35f
        }
        val drawW = if (busy) shownW + 2 * 34f.dpf * scale else shownW
        val top = max(screen.top + 2f.dpf * scale, screen.top + sbh / 2f - shownH / 2f + shownY)
        val island = RectF(cx + shownX - drawW / 2f, top, cx + shownX + drawW / 2f, top + shownH)
        paint.color = Color.BLACK
        canvas.drawRoundRect(island, shownH / 2f, shownH / 2f, paint)
        if (busy) {
            // Something playing: art on the left, waveform on the right.
            val r = min(shownH * 0.62f, 12f.dpf)
            paint.color = accent
            val ax = island.left + shownH * 0.22f
            canvas.drawRoundRect(RectF(ax, island.centerY() - r / 2f, ax + r, island.centerY() + r / 2f), r * 0.28f, r * 0.28f, paint)
            val bars = 4
            val bwid = max(1f, r * 0.14f)
            var bx2 = island.right - shownH * 0.22f - r
            for (i in 0 until bars) {
                val f = listOf(0.45f, 0.9f, 0.6f, 0.8f)[i]
                canvas.drawRoundRect(RectF(bx2, island.centerY() - r * f / 2f, bx2 + bwid, island.centerY() + r * f / 2f), bwid / 2f, bwid / 2f, paint)
                bx2 += bwid * 2.1f
            }
        }
        if (kotlin.math.abs(shownW - iw) > 0.5f || kotlin.math.abs(shownH - ih) > 0.5f ||
            kotlin.math.abs(shownX - ox) > 0.5f || kotlin.math.abs(shownY - oy) > 0.5f
        ) postInvalidateOnAnimation()
    }
}
