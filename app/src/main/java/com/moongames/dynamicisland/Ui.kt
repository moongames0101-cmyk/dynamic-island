package com.moongames.dynamicisland

import android.content.Context
import android.content.res.Resources
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

const val MATCH = -1
const val WRAP = -2

val Int.dp: Int get() = Math.round(this * Resources.getSystem().displayMetrics.density)
val Float.dpf: Float get() = this * Resources.getSystem().displayMetrics.density

object C {
    val WHITE = Color.WHITE
    val GRAY = 0xFF8E8E93.toInt()
    val LIGHT = 0xFFD1D1D6.toInt()
    val CARD = 0xFF1C1C1E.toInt()
    val CARD2 = 0xFF2C2C2E.toInt()
    val LINE = 0xFF38383A.toInt()
    val BLUE = 0xFF0A84FF.toInt()
    val GREEN = 0xFF30D158.toInt()
    val ORANGE = 0xFFFF9F0A.toInt()
    val RED = 0xFFFF453A.toInt()
    val PURPLE = 0xFFBF5AF2.toInt()
    val YELLOW = 0xFFFFD60A.toInt()
}

fun Context.label(
    text: CharSequence,
    sizeSp: Float,
    color: Int,
    bold: Boolean = false,
    lines: Int = 1
): TextView {
    val tv = TextView(this)
    tv.text = text
    tv.textSize = sizeSp
    tv.setTextColor(color)
    tv.typeface = if (bold) Typeface.create("sans-serif", Typeface.BOLD)
    else Typeface.create("sans-serif", Typeface.NORMAL)
    tv.includeFontPadding = false
    if (lines == 1) {
        tv.setSingleLine(true)
        tv.ellipsize = TextUtils.TruncateAt.END
    } else if (lines > 1) {
        tv.maxLines = lines
        tv.ellipsize = TextUtils.TruncateAt.END
    }
    return tv
}

fun Context.hbox(gravity: Int = Gravity.CENTER_VERTICAL): LinearLayout {
    val l = LinearLayout(this)
    l.orientation = LinearLayout.HORIZONTAL
    l.gravity = gravity
    return l
}

fun Context.vbox(): LinearLayout {
    val l = LinearLayout(this)
    l.orientation = LinearLayout.VERTICAL
    return l
}

fun llp(w: Int, h: Int, weight: Float = 0f): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(w, h, weight)

fun flp(w: Int, h: Int, gravity: Int = Gravity.NO_GRAVITY): FrameLayout.LayoutParams =
    FrameLayout.LayoutParams(w, h, gravity)

fun roundRect(color: Int, radius: Float): GradientDrawable {
    val d = GradientDrawable()
    d.setColor(color)
    d.cornerRadius = radius
    return d
}

fun View.roundCorners(radius: Float) {
    outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, radius)
        }
    }
    clipToOutline = true
}

fun Context.tinted(res: Int, color: Int): Drawable? {
    val d = getDrawable(res) ?: return null
    val m = d.mutate()
    m.setTint(color)
    return m
}

fun fmtDuration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
    else String.format(Locale.US, "%d:%02d", m, sec)
}
