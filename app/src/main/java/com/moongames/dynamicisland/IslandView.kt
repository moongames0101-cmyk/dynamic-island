package com.moongames.dynamicisland

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.TextClock
import kotlin.math.abs
import kotlin.math.max

/**
 * The black pill. It morphs between sizes with a springy animation and swaps its content
 * with a cross-fade, just like the real Dynamic Island.
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
                    pill.animate().scaleX(0.96f).scaleY(0.96f).setStartDelay(0).setDuration(90)
                        .setInterpolator(DecelerateInterpolator()).start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    pill.animate().scaleX(1f).scaleY(1f).setDuration(260)
                        .setInterpolator(OvershootInterpolator(3f)).start()
                }
            }
            detector.onTouchEvent(ev)
        }
    }

    fun bounce() {
        pill.pivotX = pill.width / 2f
        pill.animate().scaleX(1.08f).scaleY(1.08f).setStartDelay(0).setDuration(110)
            .setInterpolator(DecelerateInterpolator()).withEndAction {
            pill.animate().scaleX(1f).scaleY(1f).setDuration(300)
                .setInterpolator(OvershootInterpolator(3f)).start()
        }.start()
    }

    /** Animate to a new size/corner radius and show [newContent] inside. */
    fun morph(w: Int, h: Int, r: Float, newContent: View) {
        anim?.cancel()
        val sw = curW
        val sh = curH
        val sr = curR

        val old = content
        if (newContent !== old) {
            if (old != null) {
                old.animate().cancel()
                old.animate().alpha(0f).setStartDelay(0).setDuration(110).withEndAction { pill.removeView(old) }.start()
            }
            newContent.alpha = 0f
            pill.addView(newContent, flp(max(w, 1), max(h, 1), Gravity.CENTER))
            newContent.animate().alpha(1f).setStartDelay(if (old == null) 0L else 150L).setDuration(220).start()
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

        // Give the window a little headroom for the overshoot, then animate the pill inside it.
        svc.resizeIsland((max(sw, w) * 1.07f).toInt() + 6, (max(sh, h) * 1.1f).toInt() + 6)
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = 470
        a.interpolator = OvershootInterpolator(1.1f)
        a.addUpdateListener {
            val f = it.animatedValue as Float
            applySize(
                (sw + (w - sw) * f).toInt(),
                (sh + (h - sh) * f).toInt(),
                sr + (r - sr) * f.coerceIn(0f, 1f)
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

    private fun applySize(w: Int, h: Int, r: Float) {
        curW = max(0, w)
        curH = max(0, h)
        curR = r
        val p = pill.layoutParams as FrameLayout.LayoutParams
        p.width = curW
        p.height = curH
        pill.layoutParams = p
        bg.cornerRadius = r
        pill.visibility = if (curW <= 2 || curH <= 2) View.INVISIBLE else View.VISIBLE
        pill.invalidateOutline()
    }
}

/** Black strip over the status bar that shows only the time. */
class CoverView(ctx: Context) : FrameLayout(ctx) {
    private val box = FrameLayout(ctx)
    private val clock = TextClock(ctx)

    init {
        setBackgroundColor(Color.BLACK)
        clock.format12Hour = "h:mm"
        clock.format24Hour = "HH:mm"
        clock.setTextColor(Color.WHITE)
        clock.textSize = 15.5f
        clock.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        clock.includeFontPadding = false
        box.addView(clock, flp(WRAP, WRAP, Gravity.CENTER))
        addView(box, flp(200, MATCH, Gravity.START))
    }

    /** Centre the clock in the gap between the screen edge and the island. */
    fun place(right: Boolean, earWidth: Int) {
        box.layoutParams = flp(earWidth, MATCH, if (right) Gravity.END else Gravity.START)
    }
}
