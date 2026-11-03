package com.moongames.dynamicisland

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

/** Sizes the island can take, worked out from the user's settings and the screen width. */
class Geo(val idleW: Int, val idleH: Int, val screenW: Int) {
    val cardW: Int get() = min(screenW - 14.dp, 460.dp)
    val bannerW: Int get() = min(screenW - 44.dp, 340.dp)
    val bannerH: Int get() = idleH + 4.dp
}

abstract class Panel(val ctx: Context) {
    abstract val view: View
    var width = 0
    var height = 0
    var radius = 0f

    open fun tick() {}

    /** Re-reads the item; returns true when the panel's size changed. */
    open fun refresh(): Boolean = false
}

object Panels {
    fun kindColor(kind: LiveKind): Int = when (kind) {
        LiveKind.CALL -> C.GREEN
        LiveKind.TIMER -> C.ORANGE
        LiveKind.NAV -> C.BLUE
        LiveKind.PROGRESS -> C.BLUE
        LiveKind.MEDIA -> Color.WHITE
        LiveKind.PROMPT -> C.PURPLE
    }

    fun kindIcon(ctx: Context, kind: LiveKind): Drawable? {
        val res = when (kind) {
            LiveKind.CALL -> R.drawable.ic_call
            LiveKind.TIMER -> R.drawable.ic_timer
            LiveKind.NAV -> R.drawable.ic_nav
            LiveKind.PROGRESS -> R.drawable.ic_download
            LiveKind.MEDIA -> R.drawable.ic_play
            LiveKind.PROMPT -> R.drawable.ic_sparkle
        }
        return ctx.tinted(res, kindColor(kind))
    }

    fun idle(ctx: Context, g: Geo, visible: Boolean): Panel = IdlePanel(ctx, g, visible)
    fun compact(svc: IslandService, item: LiveItem, g: Geo): Panel = CompactPanel(svc, item, g)

    fun expanded(svc: IslandService, item: LiveItem, g: Geo): Panel = when {
        item.kind == LiveKind.MEDIA && item.media != null -> MediaPanel(svc, item, g)
        item.kind == LiveKind.PROMPT -> PromptPanel(svc, item, g)
        else -> GenericPanel(svc, item, g)
    }

    fun toast(svc: IslandService, t: ToastItem, g: Geo): Panel =
        if (t.style == ToastStyle.CARD) CardToastPanel(svc, t, g) else BannerPanel(svc, t, g)

    fun actionColor(title: String): Int {
        val t = title.lowercase()
        return when {
            listOf("decline", "reject", "hang", "end", "cancel", "stop", "dismiss").any { t.contains(it) } -> C.RED
            listOf("answer", "accept", "pick up").any { t.contains(it) } -> C.GREEN
            else -> C.CARD2
        }
    }
}

private class IdlePanel(ctx: Context, g: Geo, visible: Boolean) : Panel(ctx) {
    override val view: View = View(ctx)

    init {
        width = if (visible) g.idleW else 0
        height = if (visible) g.idleH else 0
        radius = g.idleH / 2f
    }
}

/** Island with something on each side of the camera (album art + waveform, timer, call…). */
private class CompactPanel(private val svc: IslandService, private val item: LiveItem, private val g: Geo) : Panel(svc) {
    private val root = FrameLayout(svc)
    override val view: View get() = root
    private val iconSize = (g.idleH - 10.dp).coerceIn(16.dp, 30.dp)
    private val leftImg = ImageView(svc)
    private val leftEmoji = svc.label("", 15f, Color.WHITE)
    private val useEmoji = item.kind == LiveKind.PROMPT && item.emoji.isNotEmpty()
    private var rightText: TextView? = null
    private var wave: WaveformView? = null
    private var ring: RingView? = null

    init {
        height = g.idleH
        radius = g.idleH / 2f
        leftImg.scaleType = ImageView.ScaleType.CENTER_CROP
        when (item.kind) {
            LiveKind.MEDIA -> leftImg.roundCorners(7f.dpf)
            LiveKind.NAV, LiveKind.PROGRESS -> leftImg.roundCorners(iconSize / 2f)
            else -> {}
        }
        if (useEmoji) {
            root.addView(leftEmoji, flp(WRAP, WRAP, Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = 12.dp })
        } else {
            val m = ((g.idleH - iconSize) / 2).coerceAtLeast(6.dp) + 2.dp
            root.addView(leftImg, flp(iconSize, iconSize, Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = m })
        }
        when (item.kind) {
            LiveKind.MEDIA -> {
                val w = WaveformView(svc)
                wave = w
                root.addView(w, flp(20.dp, 15.dp, Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = 13.dp })
            }
            LiveKind.PROGRESS -> {
                val r = RingView(svc)
                ring = r
                root.addView(r, flp(18.dp, 18.dp, Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = 12.dp })
            }
            else -> {
                val t = svc.label("", 14f, Panels.kindColor(item.kind), bold = true)
                t.fontFeatureSettings = "tnum"
                t.maxWidth = 120.dp
                rightText = t
                root.addView(t, flp(WRAP, WRAP, Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = 13.dp })
            }
        }
        bind()
        width = computeWidth()
    }

    private fun bind() {
        when (item.kind) {
            LiveKind.MEDIA -> {
                leftImg.setImageDrawable(item.icon)
                wave?.tint = item.accent
                wave?.setPlaying(item.media?.playing == true)
            }
            LiveKind.CALL -> {
                leftImg.setImageDrawable(Panels.kindIcon(svc, LiveKind.CALL))
                rightText?.text = if (item.hasChrono) item.chronoText() else if (item.incoming) "Incoming" else "Call"
            }
            LiveKind.TIMER -> {
                leftImg.setImageDrawable(Panels.kindIcon(svc, LiveKind.TIMER))
                rightText?.text = if (item.hasChrono) item.chronoText() else item.text.ifEmpty { "Paused" }
            }
            LiveKind.NAV -> {
                leftImg.setImageDrawable(item.icon ?: Panels.kindIcon(svc, LiveKind.NAV))
                rightText?.setTextColor(Color.WHITE)
                rightText?.text = item.title.ifEmpty { item.text }
            }
            LiveKind.PROGRESS -> {
                leftImg.setImageDrawable(item.icon ?: Panels.kindIcon(svc, LiveKind.PROGRESS))
                ring?.progress = item.progress
            }
            LiveKind.PROMPT -> {
                if (useEmoji) leftEmoji.text = item.emoji
                else leftImg.setImageDrawable(Panels.kindIcon(svc, LiveKind.PROMPT))
                rightText?.setTextColor(Color.WHITE)
                rightText?.text = item.title
            }
        }
    }

    private fun computeWidth(): Int {
        val rt = rightText
        val rightW = when {
            rt != null -> {
                val shown = rt.text.toString()
                val template = if (item.hasChrono && shown.length <= 5) "88:88" else shown
                min(max(rt.paint.measureText(shown), rt.paint.measureText(template)).toInt(), 120.dp) + 24.dp
            }
            wave != null -> 20.dp + 24.dp
            else -> 18.dp + 24.dp
        }
        val leftW = if (useEmoji) 34.dp else iconSize + 18.dp
        val side = max(max(leftW, rightW), 42.dp)
        return min(g.idleW + 2 * side, g.cardW)
    }

    override fun tick() {
        if (item.hasChrono && (item.kind == LiveKind.TIMER || item.kind == LiveKind.CALL)) {
            rightText?.text = item.chronoText()
        }
    }

    override fun refresh(): Boolean {
        bind()
        val w = computeWidth()
        val changed = w != width
        width = w
        return changed
    }
}

/** Big music player (tap the island while music plays). */
private class MediaPanel(private val svc: IslandService, private val item: LiveItem, g: Geo) : Panel(svc) {
    private val root = FrameLayout(svc)
    override val view: View get() = root
    private val art = ImageView(svc)
    private val title = svc.label("", 16f, Color.WHITE, bold = true)
    private val artist = svc.label("", 13.5f, C.GRAY)
    private val wave = WaveformView(svc)
    private val seek = SeekBar(svc)
    private val elapsed = svc.label("0:00", 11.5f, C.GRAY)
    private val remain = svc.label("-0:00", 11.5f, C.GRAY)
    private val play = ImageView(svc)
    private var dragging = false

    init {
        width = g.cardW
        height = 182.dp
        radius = 42f.dpf

        val col = svc.vbox()
        col.setPadding(20.dp, 18.dp, 20.dp, 10.dp)

        val top = svc.hbox()
        art.scaleType = ImageView.ScaleType.CENTER_CROP
        art.roundCorners(14f.dpf)
        top.addView(art, llp(56.dp, 56.dp))
        val txt = svc.vbox()
        txt.setPadding(14.dp, 0, 10.dp, 0)
        txt.addView(title)
        artist.setPadding(0, 5.dp, 0, 0)
        txt.addView(artist)
        top.addView(txt, llp(0, WRAP, 1f))
        top.addView(wave, llp(26.dp, 20.dp))
        col.addView(top, llp(MATCH, WRAP))

        val bar = svc.hbox()
        bar.setPadding(0, 12.dp, 0, 0)
        seek.max = 1000
        seek.progressTintList = ColorStateList.valueOf(Color.WHITE)
        seek.progressBackgroundTintList = ColorStateList.valueOf(0x55FFFFFF)
        seek.thumbTintList = ColorStateList.valueOf(Color.WHITE)
        seek.splitTrack = false
        seek.setPadding(12.dp, 0, 12.dp, 0)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) svc.touched()
            }

            override fun onStartTrackingTouch(s: SeekBar?) {
                dragging = true
                svc.touched()
            }

            override fun onStopTrackingTouch(s: SeekBar?) {
                dragging = false
                val m = item.media ?: return
                if (m.duration > 0) {
                    try {
                        m.controller.transportControls.seekTo(seek.progress.toLong() * m.duration / 1000L)
                    } catch (e: Exception) {
                    }
                }
            }
        })
        bar.addView(elapsed, llp(42.dp, WRAP))
        bar.addView(seek, llp(0, 24.dp, 1f))
        remain.gravity = Gravity.END
        bar.addView(remain, llp(46.dp, WRAP))
        col.addView(bar, llp(MATCH, WRAP))

        val controls = svc.hbox(Gravity.CENTER)
        controls.setPadding(0, 4.dp, 0, 0)
        controls.addView(control(R.drawable.ic_prev) { item.media?.controller?.transportControls?.skipToPrevious() }, llp(44.dp, 44.dp))
        controls.addView(View(svc), llp(30.dp, 1))
        play.setOnClickListener {
            svc.touched()
            val m = item.media
            if (m != null) {
                try {
                    if (m.playing) m.controller.transportControls.pause() else m.controller.transportControls.play()
                } catch (e: Exception) {
                }
            }
        }
        play.setPadding(6.dp, 6.dp, 6.dp, 6.dp)
        controls.addView(play, llp(50.dp, 50.dp))
        controls.addView(View(svc), llp(30.dp, 1))
        controls.addView(control(R.drawable.ic_next) { item.media?.controller?.transportControls?.skipToNext() }, llp(44.dp, 44.dp))
        col.addView(controls, llp(MATCH, WRAP))

        root.addView(col, flp(MATCH, MATCH))
        bind()
    }

    private fun control(res: Int, action: () -> Unit): ImageView {
        val v = ImageView(svc)
        v.setImageResource(res)
        v.setPadding(9.dp, 9.dp, 9.dp, 9.dp)
        v.setOnClickListener {
            svc.touched()
            try { action() } catch (e: Exception) { }
        }
        return v
    }

    private fun bind() {
        val m = item.media
        art.setImageDrawable(item.icon)
        title.text = item.title
        artist.text = item.text.ifEmpty { item.appName }
        wave.tint = item.accent
        wave.setPlaying(m?.playing == true)
        play.setImageResource(if (m?.playing == true) R.drawable.ic_pause else R.drawable.ic_play)
        tick()
    }

    override fun tick() {
        val m = item.media ?: return
        if (m.duration <= 0) {
            seek.visibility = View.INVISIBLE
            elapsed.text = ""
            remain.text = ""
            return
        }
        seek.visibility = View.VISIBLE
        val pos = m.currentPosition().coerceIn(0L, m.duration)
        if (!dragging) seek.progress = (pos * 1000L / m.duration).toInt()
        elapsed.text = fmtDuration(pos)
        remain.text = "-" + fmtDuration(m.duration - pos)
    }

    override fun refresh(): Boolean {
        bind()
        return false
    }
}

/** Expanded call / timer / directions / download. */
private class GenericPanel(private val svc: IslandService, private val item: LiveItem, g: Geo) : Panel(svc) {
    private val root = FrameLayout(svc)
    override val view: View get() = root
    private val icon = ImageView(svc)
    private val app = svc.label("", 12f, C.GRAY)
    private val title = svc.label("", 16f, Color.WHITE, bold = true)
    private val text = svc.label("", 13.5f, C.LIGHT, lines = 2)
    private val big = svc.label("", 26f, Panels.kindColor(item.kind), bold = true)
    private var ring: RingView? = null

    init {
        width = g.cardW
        val hasActions = item.actions.isNotEmpty()
        height = if (hasActions) 150.dp else 98.dp
        radius = min(height / 2f, 42f.dpf)

        val col = svc.vbox()
        col.setPadding(18.dp, 16.dp, 18.dp, 14.dp)
        val top = svc.hbox()
        icon.scaleType = ImageView.ScaleType.CENTER_CROP
        icon.roundCorners(23f.dpf)
        top.addView(icon, llp(46.dp, 46.dp))
        val txt = svc.vbox()
        txt.setPadding(12.dp, 0, 8.dp, 0)
        txt.addView(app)
        title.setPadding(0, 3.dp, 0, 0)
        txt.addView(title)
        text.setPadding(0, 3.dp, 0, 0)
        txt.addView(text)
        top.addView(txt, llp(0, WRAP, 1f))
        if (item.kind == LiveKind.PROGRESS) {
            val r = RingView(svc)
            ring = r
            top.addView(r, llp(36.dp, 36.dp))
        } else {
            big.fontFeatureSettings = "tnum"
            top.addView(big, llp(WRAP, WRAP))
        }
        col.addView(top, llp(MATCH, WRAP))

        if (hasActions) {
            val row = svc.hbox()
            row.setPadding(0, 14.dp, 0, 0)
            item.actions.forEachIndexed { i, a ->
                val b = svc.label(a.title, 14f, Color.WHITE, bold = true)
                b.gravity = Gravity.CENTER
                b.background = roundRect(Panels.actionColor(a.title.toString()), 21f.dpf)
                b.setOnClickListener {
                    svc.touched()
                    svc.sendPending(a.intent)
                    if (item.kind == LiveKind.CALL) svc.collapse()
                }
                row.addView(b, llp(0, 42.dp, 1f).apply { if (i > 0) marginStart = 10.dp })
            }
            col.addView(row, llp(MATCH, WRAP))
        }
        root.addView(col, flp(MATCH, MATCH))
        bind()
    }

    private fun bind() {
        icon.setImageDrawable(item.icon ?: Panels.kindIcon(svc, item.kind))
        app.text = item.appName
        title.text = item.title.ifEmpty { item.appName }
        text.text = when {
            item.kind == LiveKind.CALL && item.incoming && item.text.isEmpty() -> "Incoming call"
            else -> item.text
        }
        text.visibility = if (text.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        ring?.progress = item.progress
        tick()
    }

    override fun tick() {
        big.text = if (item.hasChrono) item.chronoText()
        else if (item.kind == LiveKind.PROGRESS && item.progress >= 0f) "${(item.progress * 100).toInt()}%"
        else ""
    }

    override fun refresh(): Boolean {
        bind()
        return false
    }
}

/** A pinned prompt, expanded. */
private class PromptPanel(private val svc: IslandService, private val item: LiveItem, g: Geo) : Panel(svc) {
    private val root = FrameLayout(svc)
    override val view: View get() = root

    init {
        width = g.cardW
        height = 150.dp
        radius = 42f.dpf
        val col = svc.vbox()
        col.setPadding(20.dp, 18.dp, 20.dp, 14.dp)
        val top = svc.hbox()
        if (item.emoji.isNotEmpty()) {
            top.addView(svc.label(item.emoji, 28f, Color.WHITE), llp(WRAP, WRAP))
        } else {
            top.addView(ImageView(svc).apply { setImageDrawable(Panels.kindIcon(svc, LiveKind.PROMPT)) }, llp(32.dp, 32.dp))
        }
        val t = svc.label(item.title, 16f, Color.WHITE, bold = true, lines = 3)
        t.setPadding(14.dp, 0, 0, 0)
        top.addView(t, llp(0, WRAP, 1f))
        col.addView(top, llp(MATCH, 0, 1f))

        val row = svc.hbox()
        val close = svc.label("Close", 14f, Color.WHITE, bold = true)
        close.gravity = Gravity.CENTER
        close.background = roundRect(C.CARD2, 21f.dpf)
        close.setOnClickListener { svc.collapse() }
        row.addView(close, llp(0, 42.dp, 1f))
        val unpin = svc.label("Unpin", 14f, Color.WHITE, bold = true)
        unpin.gravity = Gravity.CENTER
        unpin.background = roundRect(C.CARD2, 21f.dpf)
        unpin.setOnClickListener { svc.prefs.edit().remove(P.PINNED).apply() }
        row.addView(unpin, llp(0, 42.dp, 1f).apply { marginStart = 10.dp })
        col.addView(row, llp(MATCH, WRAP))
        root.addView(col, flp(MATCH, MATCH))
    }
}

/** Notification-style pop-out: icon, app name, title, message. */
private class CardToastPanel(svc: IslandService, t: ToastItem, g: Geo) : Panel(svc) {
    private val root = FrameLayout(svc)
    override val view: View get() = root

    init {
        width = g.cardW
        height = if (t.text.isBlank()) 76.dp else 90.dp
        radius = min(height / 2f, 42f.dpf)

        val row = svc.hbox()
        row.setPadding(16.dp, 0, 20.dp, 0)
        if (t.icon != null) {
            val iv = ImageView(svc)
            iv.setImageDrawable(t.icon)
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            iv.roundCorners(24f.dpf)
            row.addView(iv, llp(48.dp, 48.dp))
        } else {
            val e = svc.label(t.emoji.ifEmpty { "✨" }, 28f, Color.WHITE)
            e.gravity = Gravity.CENTER
            row.addView(e, llp(48.dp, 48.dp))
        }
        val col = svc.vbox()
        col.setPadding(13.dp, 0, 0, 0)
        if (t.appName.isNotEmpty()) {
            col.addView(svc.label(t.appName, 12f, C.GRAY))
        }
        val title = svc.label(t.title, 15.5f, Color.WHITE, bold = true, lines = if (t.text.isBlank()) 2 else 1)
        title.setPadding(0, 2.dp, 0, 0)
        col.addView(title)
        if (t.text.isNotBlank()) {
            val body = svc.label(t.text, 13.5f, C.LIGHT, lines = 2)
            body.setPadding(0, 2.dp, 0, 0)
            col.addView(body)
        }
        row.addView(col, llp(0, WRAP, 1f))
        root.addView(row, flp(MATCH, MATCH))
    }
}

/** Wide pill: label on the left of the camera, value on the right (Charging 82% ▮). */
private class BannerPanel(svc: IslandService, t: ToastItem, g: Geo) : Panel(svc) {
    private val root = FrameLayout(svc)
    override val view: View get() = root

    init {
        width = g.bannerW
        height = g.bannerH
        radius = height / 2f

        val left = svc.hbox()
        if (t.icon != null) {
            val iv = ImageView(svc)
            iv.setImageDrawable(t.icon)
            left.addView(iv, llp(20.dp, 20.dp))
        } else if (t.emoji.isNotEmpty()) {
            left.addView(svc.label(t.emoji, 15f, Color.WHITE), llp(WRAP, WRAP))
        }
        val title = svc.label(t.title, 14.5f, Color.WHITE, bold = true)
        title.maxWidth = (width / 2 - 40.dp).coerceAtLeast(40.dp)
        title.setPadding(if (left.childCount > 0) 8.dp else 0, 0, 0, 0)
        left.addView(title, llp(WRAP, WRAP))
        root.addView(left, flp(WRAP, WRAP, Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = 16.dp })

        val right = svc.hbox()
        if (t.value.isNotEmpty()) {
            right.addView(svc.label(t.value, 14.5f, t.accent, bold = true), llp(WRAP, WRAP))
        }
        if (t.battery >= 0) {
            val b = BatteryView(svc)
            b.set(t.battery, t.accent, t.charging)
            right.addView(b, llp(27.dp, 13.dp).apply { marginStart = 7.dp })
        }
        root.addView(right, flp(WRAP, WRAP, Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = 16.dp })
    }
}

@Suppress("unused")
private fun LinearLayout.gap(px: Int) = addView(View(context), llp(px, 1))
