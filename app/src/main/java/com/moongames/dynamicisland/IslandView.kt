package com.moongames.dynamicisland

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A damped spring, like the ones iOS uses. [damping] < 1 gives a small, soft overshoot.
 * Normalised so the motion has settled by the end of the animation.
 */
class Spring(private val damping: Float = 0.7f) : TimeInterpolator {
    private val w0 = 6.4f / damping
    private val wd = w0 * sqrt(1f - damping * damping)

    override fun getInterpolation(t: Float): Float {
        if (t <= 0f) return 0f
        if (t >= 1f) return 1f
        val e = exp(-damping * w0 * t)
        return 1f - e * (cos(wd * t) + (damping * w0 / wd) * sin(wd * t))
    }
}

/**
 * The black pill. It morphs between sizes with spring physics and swaps its content with a
 * blur-and-zoom cross-fade, like the real Dynamic Island.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class IslandView(ctx: Context, private val svc: IslandService) : FrameLayout(ctx) {

    val pill = FrameLayout(ctx)
    private val bg = GradientDrawable()
    private var curW = 0
    private var curH = 0
    private var curR = 0f
    private var anim: ValueAnimator? = null
    private var content: View? = null
    private val springSize = Spring(0.68f)
    private val springSoft = Spring(0.85f)
    private val easeOut = PathInterpolator(0.2f, 0f, 0f, 1f)

    private val detector = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            svc.onIslandTap()
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            svc.onIslandLongPress()
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (velocityY < -350f && abs(velocityY) > abs(velocityX)) {
                svc.onIslandSwipeUp()
                return true
            }
            return false
        }
    })

    init {
        clipChildren = false
        bg.setColor(Color.BLACK)
        pill.background = bg
        pill.pivotY = 0f
        pill.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, curR)
            }
        }
        pill.clipToOutline = true
        addView(pill, flp(0, 0, Gravity.TOP or Gravity.CENTER_HORIZONTAL))

        setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_OUTSIDE -> {
                    svc.onOutsideTouch()
                    return@setOnTouchListener false
                }
                MotionEvent.ACTION_DOWN -> {
                    pill.pivotX = pill.width / 2f
                    pill.animate().scaleX(0.95f).scaleY(0.95f).setStartDelay(0).setDuration(120)
                        .setInterpolator(easeOut).start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    pill.animate().scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(420)
                        .setInterpolator(springSize).start()
                }
            }
            detector.onTouchEvent(ev)
        }
    }

    fun bounce() {
        pill.pivotX = pill.width / 2f
        pill.animate().scaleX(1.1f).scaleY(1.06f).setStartDelay(0).setDuration(120)
            .setInterpolator(easeOut).withEndAction {
                pill.animate().scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(500)
                    .setInterpolator(Spring(0.45f)).start()
            }.start()
    }

    /** Animate to a new size/corner radius and show [newContent] inside. */
    fun morph(w: Int, h: Int, r: Float, newContent: View) {
        anim?.cancel()
        val sw = curW
        val sh = curH
        val sr = curR
        val growing = w * h >= sw * sh

        val old = content
        if (newContent !== old) {
            if (old != null) contentOut(old)
            pill.addView(newContent, flp(max(w, 1), max(h, 1), Gravity.CENTER))
            contentIn(newContent, if (old == null) 60L else 140L)
            content = newContent
        } else {
            val p = newContent.layoutParams as FrameLayout.LayoutParams
            p.width = max(w, 1)
            p.height = max(h, 1)
            newContent.layoutParams = p
        }

        if (sw == w && sh == h && sr == r) {
            applySize(w, h, r)
            svc.resizeIsland(max(w, 1), max(h, 1))
            return
        }

        // Window gets headroom for the overshoot; the pill animates inside it.
        svc.resizeIsland((max(sw, w) * 1.08f).toInt() + 8, (max(sh, h) * 1.12f).toInt() + 8)

        // Like iOS: when growing, the width leads and the height follows a beat later;
        // when shrinking, the height tucks in first.
        val total = 620L
        val lag = 70f / total
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = total
        a.interpolator = LinearInterpolator()
        a.addUpdateListener {
            val t = it.animatedValue as Float
            val tLead = (t / (1f - lag)).coerceAtMost(1f)
            val tLag = ((t - lag) / (1f - lag)).coerceIn(0f, 1f)
            val fw = springSize.getInterpolation(if (growing) tLead else tLag)
            val fh = springSize.getInterpolation(if (growing) tLag else tLead)
            val fr = springSoft.getInterpolation(tLead)
            applySize(
                (sw + (w - sw) * fw).toInt(),
                (sh + (h - sh) * fh).toInt(),
                sr + (r - sr) * fr.coerceIn(0f, 1f)
            )
        }
        a.addListener(object : AnimatorListenerAdapter() {
            private var cancelled = false
            override fun onAnimationCancel(animation: Animator) {
                cancelled = true
            }

            override fun onAnimationEnd(animation: Animator) {
                if (cancelled) return
                applySize(w, h, r)
                svc.resizeIsland(max(w, 1), max(h, 1))
            }
        })
        anim = a
        a.start()
    }

    private fun contentIn(v: View, delay: Long) {
        v.alpha = 0f
        v.scaleX = 0.88f
        v.scaleY = 0.88f
        v.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(delay).setDuration(380)
            .setInterpolator(springSoft).start()
        blur(v, 18f, 0f, delay, 300L, null)
    }

    private fun contentOut(v: View) {
        v.animate().cancel()
        v.animate().alpha(0f).scaleX(0.9f).scaleY(0.9f).setStartDelay(0).setDuration(150)
            .setInterpolator(easeOut).withEndAction { pill.removeView(v) }.start()
        blur(v, 0f, 18f, 0L, 150L, null)
    }

    /** Gaussian blur on Android 12+; plain fade on older phones. */
    private fun blur(v: View, from: Float, to: Float, delay: Long, dur: Long, end: (() -> Unit)?) {
        if (Build.VERSION.SDK_INT < 31) {
            end?.invoke()
            return
        }
        val a = ValueAnimator.ofFloat(from, to)
        a.startDelay = delay
        a.duration = dur
        a.interpolator = easeOut
        a.addUpdateListener {
            val r = it.animatedValue as Float
            v.setRenderEffect(if (r < 0.5f) null else RenderEffect.createBlurEffect(r, r, Shader.TileMode.DECAL))
        }
        a.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (to < 0.5f) v.setRenderEffect(null)
                end?.invoke()
            }
        })
        if (from >= 0.5f) v.setRenderEffect(RenderEffect.createBlurEffect(from, from, Shader.TileMode.DECAL))
        a.start()
    }

    private fun applySize(w: Int, h: Int, r: Float) {
        curW = max(0, w)
        curH = max(0, h)
        curR = r.coerceAtMost(curH / 2f).coerceAtLeast(0f)
        val p = pill.layoutParams as FrameLayout.LayoutParams
        p.width = curW
        p.height = curH
        pill.layoutParams = p
        bg.cornerRadius = curR
        pill.visibility = if (curW <= 2 || curH <= 2) View.INVISIBLE else View.VISIBLE
        pill.invalidateOutline()
    }

    @Suppress("unused")
    private val twoPi = (2 * PI).toFloat()
}
