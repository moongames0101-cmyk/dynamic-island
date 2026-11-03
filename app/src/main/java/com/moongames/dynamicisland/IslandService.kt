package com.moongames.dynamicisland

import android.accessibilityservice.AccessibilityService
import android.app.ActivityOptions
import android.app.KeyguardManager
import android.app.PendingIntent
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import java.util.Calendar
import java.util.Locale
import kotlin.math.max

class IslandService : AccessibilityService(), SharedPreferences.OnSharedPreferenceChangeListener {

    companion object {
        @Volatile
        var instance: IslandService? = null
        const val ACTION_PROMPT = "com.moongames.dynamicisland.SHOW_PROMPT"
        const val MEDIA_KEY = "media"
        const val PROMPT_KEY = "pinned_prompt"
        private const val DEMO_TIMER = "demo_timer"
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    lateinit var prefs: SharedPreferences

    private var islandView: IslandView? = null
    private var islandLp: WindowManager.LayoutParams? = null
    private var touchView: View? = null
    private var touchLp: WindowManager.LayoutParams? = null
    private var probeView: View? = null

    val lives = LinkedHashMap<String, LiveItem>()
    private val queue = ArrayDeque<ToastItem>()
    private var toast: ToastItem? = null
    private var expandedKey: String? = null
    private var stateId = ""
    private var panel: Panel? = null

    private var fullscreen = false
    private var shadeOpen = false
    private var shadeWhy = ""
    private var lastError: String? = null
    private var probeCheck = 0
    private var rebindCheck = 25
    private var landscape = false
    private var islandShown = true
    private var mediaPausedAt = 0L
    private var lastTouch = 0L
    private var previewUntil = 0L
    private var baseX = 0
    private var baseY = 0

    private var lastBattery = -1
    private var lastRinger = -1
    private var torchOn = false
    private var torchReadyAt = 0L
    private var lastMinute = ""
    private var receiversOn = false
    private var torchCallback: CameraManager.TorchCallback? = null
    private var connected = false

    // ------------------------------------------------------------------ lifecycle

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        connected = true
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = Prefs.get(this)
        Prefs.seed(prefs)
        prefs.registerOnSharedPreferenceChangeListener(this)
        Updater.createChannel(this)
        landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        lastBattery = batteryLevel()
        lastRinger = (getSystemService(AUDIO_SERVICE) as AudioManager).ringerMode
        try {
            addWindows()
            lastError = null
        } catch (e: Exception) {
            android.util.Log.e("Island", "Could not add overlay windows", e)
            lastError = e.javaClass.simpleName + ": " + (e.message ?: "")
        }
        registerReceivers()
        registerTorch()
        syncPinned()
        main.post(ticker)
        main.postDelayed(updateCheck, 30_000L)
        render(true)
        NotifListener.instance?.resync()
        welcomeAfterUpdate()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val t = event?.eventType ?: return
        if (t == AccessibilityEvent.TYPE_WINDOWS_CHANGED || t == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            checkShade()
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        if (!connected) return
        connected = false
        if (instance === this) instance = null
        main.removeCallbacksAndMessages(null)
        try { prefs.unregisterOnSharedPreferenceChangeListener(this) } catch (e: Exception) { }
        if (receiversOn) {
            try { unregisterReceiver(systemReceiver) } catch (e: Exception) { }
            try { unregisterReceiver(promptReceiver) } catch (e: Exception) { }
            receiversOn = false
        }
        torchCallback?.let { cb ->
            try { (getSystemService(CAMERA_SERVICE) as CameraManager).unregisterTorchCallback(cb) } catch (e: Exception) { }
        }
        torchCallback = null
        for (v in listOf<View?>(islandView, touchView, probeView)) {
            if (v != null) try { wm.removeViewImmediate(v) } catch (e: Exception) { }
        }
        islandView = null
        touchView = null
        probeView = null
        panel = null
        stateId = ""
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        landscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        main.postDelayed({
            computePosition()
            pushIslandLayout()
            render(true)
        }, 300L)
    }

    override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
        when (key) {
            P.WIDTH, P.HEIGHT, P.OFF_X, P.OFF_Y -> {
                previewUntil = System.currentTimeMillis() + 2500L
                computePosition()
                pushIslandLayout()
                render(true)
            }
            P.PINNED, P.PROMPTS, P.F_PROMPTS -> {
                syncPinned()
                render()
            }
            P.F_MEDIA -> {
                if (!feature(P.F_MEDIA)) removeLive(MEDIA_KEY) else NotifListener.instance?.resync()
            }
            P.IDLE_VISIBLE, P.ENABLED, P.HIDE_FULLSCREEN -> render(true)
            else -> {}
        }
    }

    // ------------------------------------------------------------------ windows

    private fun overlayParams(w: Int, h: Int, touchable: Boolean): WindowManager.LayoutParams {
        // Hardware acceleration gives smooth animations, clean rounded clipping and blur.
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        flags = flags or if (touchable) {
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        } else {
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        val p = WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        )
        if (Build.VERSION.SDK_INT >= 28) {
            p.layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        if (Build.VERSION.SDK_INT >= 30) p.fitInsetsTypes = 0
        return p
    }

    @Suppress("DEPRECATION")
    private fun addWindows() {
        // 1. What you see: a fixed strip across the top that never moves or resizes, so the
        //    island can't jump around while it animates. It lets every touch through.
        val iv = IslandView(this, this)
        val ilp = overlayParams(WindowManager.LayoutParams.MATCH_PARENT, 250.dp, false)
        ilp.gravity = Gravity.TOP or Gravity.START
        islandLp = ilp
        islandView = iv

        // 2. What you touch: an invisible window sitting exactly over the island that passes
        //    taps and swipes on to it. Everywhere else, touches go to your apps as normal.
        val tv = TouchCatcher()
        val tlp = overlayParams(1, 1, true)
        tlp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        touchLp = tlp
        touchView = tv

        computePosition()
        wm.addView(iv, ilp)
        wm.addView(tv, tlp)
        applyVisibility()

        // 3. Full-screen detector (optional).
        updateProbe()
    }

    @android.annotation.SuppressLint("ViewConstructor", "ClickableViewAccessibility")
    private inner class TouchCatcher : View(this@IslandService) {
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                onOutsideTouch()
                return false
            }
            islandView?.forwardTouch(event)
            return true
        }
    }

    /**
     * Invisible 1px window that tells us when an app hides the status bar (videos, games).
     * On Android 11+ it has to sit *below* the status bar to be told about it, which needs the
     * "Display over other apps" permission. Without it we simply never hide.
     */
    @Suppress("DEPRECATION")
    private fun updateProbe() {
        val wantProbe = Build.VERSION.SDK_INT < 30 || Settings.canDrawOverlays(this)
        val existing = probeView
        if (!wantProbe) {
            if (existing != null) {
                try { wm.removeViewImmediate(existing) } catch (e: Exception) { }
                probeView = null
            }
            setFullscreen(false)
            return
        }
        if (existing != null) return
        try {
            val pv = View(this)
            val plp: WindowManager.LayoutParams
            if (Build.VERSION.SDK_INT >= 30) {
                plp = WindowManager.LayoutParams(
                    1, 1,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                )
                plp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                plp.fitInsetsTypes = 0
                pv.setOnApplyWindowInsetsListener { _, insets ->
                    // Only trust the answer if this window actually knows about a status bar.
                    val max = insets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars())
                    val known = max.top > 0 || max.left > 0 || max.right > 0 || max.bottom > 0
                    setFullscreen(known && !insets.isVisible(WindowInsets.Type.statusBars()))
                    insets
                }
            } else {
                plp = overlayParams(1, 1, false)
                pv.setOnSystemUiVisibilityChangeListener { vis ->
                    setFullscreen((vis and View.SYSTEM_UI_FLAG_FULLSCREEN) != 0)
                }
            }
            plp.gravity = Gravity.TOP or Gravity.START
            plp.alpha = 0f
            wm.addView(pv, plp)
            probeView = pv
        } catch (e: Exception) {
            probeView = null
            setFullscreen(false)
        }
    }

    /** One-line health report shown in the app, so problems are visible in a screenshot. */
    fun statusText(): String {
        lastError?.let { return "Error: $it" }
        if (islandView == null) return "Running, but the overlay couldn't be drawn"
        val why = mutableListOf<String>()
        if (!prefs.getBoolean(P.ENABLED, true)) why += "switched off"
        if (landscape) why += "phone is sideways"
        if (shadeOpen) why += "notification shade open" + (if (shadeWhy.isNotEmpty()) " ($shadeWhy)" else "")
        if (fullscreen) why += "full-screen app"
        if (why.isNotEmpty()) return "Running, hidden because: " + why.joinToString(", ")
        return if (NotifListener.instance != null) "Running ✓ — island showing, notifications connected"
        else "Running, but notifications aren't connected — turn Notification access off and on"
    }

    private fun pushIslandLayout() {
        val v = islandView
        val p = islandLp
        if (v != null && p != null) try { wm.updateViewLayout(v, p) } catch (e: Exception) { }
        pushTouchLayout()
    }

    private fun pushTouchLayout() {
        val v = touchView ?: return
        val p = touchLp ?: return
        try { wm.updateViewLayout(v, p) } catch (e: Exception) { }
    }

    /** Resizes only the invisible touch area; the visible island window never changes size. */
    fun resizeIsland(w: Int, h: Int) {
        val p = touchLp ?: return
        val nw = max(w, 1)
        val nh = max(h, 1)
        if (p.width == nw && p.height == nh) return
        p.width = nw
        p.height = nh
        pushTouchLayout()
    }

    private fun setFullscreen(fs: Boolean) {
        if (fs == fullscreen) return
        fullscreen = fs
        applyVisibility()
    }

    @Suppress("DEPRECATION")
    private fun screenWidth(): Int {
        return try {
            val d: Display? = (getSystemService(DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
            val pt = Point()
            if (d == null) resources.displayMetrics.widthPixels else {
                d.getRealSize(pt)
                pt.x
            }
        } catch (e: Exception) {
            resources.displayMetrics.widthPixels
        }
    }

    private fun topCutout(): Rect? {
        if (Build.VERSION.SDK_INT < 29 || landscape) return null
        return try {
            val d: Display? = (getSystemService(DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
            val sw = screenWidth()
            d?.cutout?.boundingRects?.firstOrNull { it.top <= 4 && it.height() > 0 && it.width() < sw / 2 }
        } catch (e: Exception) {
            null
        }
    }

    @android.annotation.SuppressLint("DiscouragedApi", "InternalInsetResource")
    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        var h = if (id > 0) resources.getDimensionPixelSize(id) else 24.dp
        topCutout()?.let { h = max(h, it.bottom) }
        return h
    }

    private fun geo(): Geo = Geo(
        prefs.getInt(P.WIDTH, AutoSize.widthDp(this)).dp,
        prefs.getInt(P.HEIGHT, AutoSize.heightDp(this)).dp,
        screenWidth()
    )

    private fun computePosition() {
        val g = geo()
        val cut = topCutout()
        val sbh = statusBarHeight()
        val centerY = cut?.centerY() ?: (sbh / 2)
        val centerX = if (cut != null) cut.centerX() - g.screenW / 2 else 0
        baseY = (centerY - g.idleH / 2).coerceAtLeast(2.dp) + prefs.getInt(P.OFF_Y, 0).dp
        baseX = centerX + prefs.getInt(P.OFF_X, 0).dp
        islandLp?.let {
            it.x = 0
            it.y = baseY.coerceAtLeast(0)
        }
        touchLp?.let {
            it.x = baseX
            it.y = baseY.coerceAtLeast(0)
        }
        islandView?.setOffsetX(baseX)
    }

    private fun applyVisibility() {
        if (!::prefs.isInitialized) return
        val enabled = prefs.getBoolean(P.ENABLED, true)
        val showIsland = enabled && !landscape && !shadeOpen &&
            !(fullscreen && prefs.getBoolean(P.HIDE_FULLSCREEN, true))
        val iv = islandView ?: return
        val p = touchLp ?: return
        if (showIsland != islandShown) {
            islandShown = showIsland
            iv.visibility = if (showIsland) View.VISIBLE else View.GONE
            touchView?.visibility = if (showIsland) View.VISIBLE else View.GONE
            p.flags = if (showIsland) p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            else p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            pushTouchLayout()
        }
    }

    /** Hide while the notification shade / quick settings are pulled down. */
    private fun checkShade() {
        var open = false
        var why = ""
        try {
            val km = getSystemService(KeyguardManager::class.java)
            if (km == null || !km.isKeyguardLocked) {
                val dm = resources.displayMetrics
                val r = Rect()
                for (w in windows) {
                    if (w.type != AccessibilityWindowInfo.TYPE_SYSTEM) continue
                    w.getBoundsInScreen(r)
                    if (r.height() <= dm.heightPixels * 0.45f || r.width() <= dm.widthPixels * 0.6f) continue
                    // The shade window is full-screen even when it's only showing a small pop-up
                    // notification, so check what part of it is actually in use.
                    var expanded = w.isFocused || w.isActive
                    if (!expanded && Build.VERSION.SDK_INT >= 33) {
                        val region = android.graphics.Region()
                        w.getRegionInScreen(region)
                        val b = region.bounds
                        expanded = b.height() > dm.heightPixels * 0.45f && b.width() > dm.widthPixels * 0.6f
                    }
                    if (expanded) {
                        open = true
                        why = w.title?.toString() ?: ""
                        break
                    }
                }
            }
        } catch (e: Exception) {
        }
        shadeWhy = why
        if (open != shadeOpen) {
            shadeOpen = open
            applyVisibility()
        }
    }

    // ------------------------------------------------------------------ rendering

    fun render(force: Boolean = false) {
        val iv = islandView ?: return
        applyVisibility()
        val g = geo()
        val t = toast
        val exp = expandedKey?.let { lives[it] }
        if (exp == null) expandedKey = null
        val top = topLive()
        val id: String
        val make: () -> Panel
        when {
            t != null -> {
                id = "toast:${t.id}"
                make = { Panels.toast(this, t, g) }
            }
            exp != null -> {
                id = "exp:" + exp.signature()
                make = { Panels.expanded(this, exp, g) }
            }
            top != null -> {
                id = "compact:" + top.signature()
                make = { Panels.compact(this, top, g) }
            }
            else -> {
                val vis = prefs.getBoolean(P.IDLE_VISIBLE, true) || System.currentTimeMillis() < previewUntil
                id = "idle:$vis"
                make = { Panels.idle(this, g, vis) }
            }
        }
        val p = panel
        if (force || p == null || id != stateId) {
            stateId = id
            val np = make()
            panel = np
            iv.morph(np.width, np.height, np.radius, np.view)
        } else if (p.refresh()) {
            iv.morph(p.width, p.height, p.radius, p.view)
        }
    }

    private fun topLive(): LiveItem? {
        var best: LiveItem? = null
        for (item in lives.values) {
            if (best == null || item.kind.priority >= best.kind.priority) best = item
        }
        return best
    }

    // ------------------------------------------------------------------ gestures

    fun onIslandTap() {
        touched()
        val t = toast
        if (t != null) {
            val action = t.onTap
            nextToast()
            action?.invoke()
            return
        }
        val exp = expandedKey?.let { lives[it] }
        if (exp != null) {
            collapse()
            openItem(exp)
            return
        }
        val top = topLive()
        if (top != null) {
            expandedKey = top.key
            render()
            return
        }
        islandView?.bounce()
    }

    fun onIslandLongPress() {
        val top = topLive()
        if (top != null && toast == null) {
            collapse()
            openItem(top)
        } else if (top == null && toast == null) {
            openSettings()
        }
    }

    fun onIslandSwipeUp() {
        if (toast != null) nextToast() else collapse()
    }

    fun onOutsideTouch() {
        collapse()
    }

    fun touched() {
        lastTouch = System.currentTimeMillis()
    }

    fun collapse() {
        if (expandedKey != null) {
            expandedKey = null
            render()
        }
    }

    private fun openItem(item: LiveItem) {
        when (item.kind) {
            LiveKind.MEDIA -> {
                val pi = item.media?.controller?.sessionActivity
                if (pi != null) sendPending(pi) else launchApp(item.pkg)
            }
            LiveKind.PROMPT -> {}
            else -> {
                val pi = item.contentIntent
                if (pi != null) sendPending(pi) else launchApp(item.pkg)
            }
        }
    }

    fun openSettings(update: Boolean = false) {
        try {
            val i = Intent(this, MainActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (update) i.putExtra(MainActivity.EXTRA_UPDATE, true)
            startActivity(i)
        } catch (e: Exception) {
        }
    }

    private fun launchApp(pkg: String) {
        try {
            val i = packageManager.getLaunchIntentForPackage(pkg) ?: return
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        } catch (e: Exception) {
        }
    }

    fun sendPending(pi: PendingIntent?) {
        if (pi == null) return
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                val opts = ActivityOptions.makeBasic()
                opts.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                pi.send(this, 0, null, null, null, null, opts.toBundle())
            } else {
                pi.send()
            }
        } catch (e: Exception) {
        }
    }

    // ------------------------------------------------------------------ pop-outs

    fun showToast(t: ToastItem) {
        if (!::prefs.isInitialized || !prefs.getBoolean(P.ENABLED, true)) return
        if (toast == null) {
            toast = t
            main.removeCallbacks(toastEnd)
            main.postDelayed(toastEnd, t.duration)
            render()
        } else {
            queue.addLast(t)
            while (queue.size > 4) queue.removeFirst()
        }
    }

    private val toastEnd = Runnable { nextToast() }

    private fun nextToast() {
        main.removeCallbacks(toastEnd)
        val n = queue.removeFirstOrNull()
        toast = n
        if (n != null) main.postDelayed(toastEnd, n.duration)
        render()
    }

    fun toastMs(): Long = prefs.getInt(P.TOAST_SECS, 4) * 1000L

    fun showPrompt(emoji: String, text: String, duration: Long = 5000L) {
        val e = emoji.trim()
        val icon = if (e.isEmpty()) tinted(R.drawable.ic_sparkle, C.PURPLE) else null
        val t = if (text.length <= 14) {
            ToastItem(ToastStyle.BANNER, title = text, emoji = e, icon = icon, accent = C.PURPLE, duration = duration)
        } else {
            ToastItem(ToastStyle.CARD, title = text, emoji = e, icon = icon, appName = "Prompt", duration = duration + 1500L)
        }
        showToast(t)
    }

    fun showUpdateToast(rel: Updater.Release) {
        showToast(
            ToastItem(
                ToastStyle.CARD,
                title = "Update available",
                text = "${rel.name} — tap to install",
                icon = getDrawable(R.mipmap.ic_launcher),
                appName = "Dynamic Island",
                duration = 7000L,
                onTap = { openSettings(update = true) }
            )
        )
    }

    private fun welcomeAfterUpdate() {
        val seen = prefs.getInt(P.SEEN_VERSION, 0)
        if (seen in 1 until BuildConfig.VERSION_CODE) {
            main.postDelayed({
                showToast(
                    ToastItem(
                        ToastStyle.BANNER, title = "Updated", value = "v${BuildConfig.VERSION_NAME}",
                        icon = tinted(R.drawable.ic_download, C.GREEN), accent = C.GREEN, duration = 3000L
                    )
                )
            }, 1500L)
        }
        if (seen != BuildConfig.VERSION_CODE) prefs.edit().putInt(P.SEEN_VERSION, BuildConfig.VERSION_CODE).apply()
    }

    // ------------------------------------------------------------------ live activities

    fun upsertLive(key: String, kind: LiveKind, autoExpand: Boolean = false, fill: (LiveItem) -> Unit) {
        val existing = lives[key]
        val item = existing ?: LiveItem(key, kind)
        item.kind = kind
        fill(item)
        if (existing == null) lives[key] = item
        if (autoExpand && existing == null) {
            expandedKey = key
            touched()
        }
        render()
    }

    fun removeLive(key: String) {
        if (lives.remove(key) != null) {
            if (expandedKey == key) expandedKey = null
            render()
        }
    }

    fun liveKind(key: String): LiveKind? = lives[key]?.kind

    fun setMedia(info: MediaInfo?) {
        if (!::prefs.isInitialized) return
        if (info == null || !feature(P.F_MEDIA)) {
            removeLive(MEDIA_KEY)
            return
        }
        val existing = lives[MEDIA_KEY]
        if (!info.playing && existing == null) return
        if (info.playing) mediaPausedAt = 0L
        else if (mediaPausedAt == 0L) mediaPausedAt = System.currentTimeMillis()
        val old = existing?.media
        val artChanged = old == null || old.art !== info.art || old.title != info.title || old.pkg != info.pkg
        upsertLive(MEDIA_KEY, LiveKind.MEDIA) { item ->
            item.media = info
            item.pkg = info.pkg
            item.title = info.title
            item.text = info.artist
            item.appName = appLabel(info.pkg)
            if (artChanged || item.icon == null) {
                item.icon = info.art?.let { BitmapDrawable(resources, it) } ?: appIcon(info.pkg)
                item.accent = Colors.vibrant(info.art)
            }
        }
    }

    private fun syncPinned() {
        val id = prefs.getLong(P.PINNED, 0L)
        val pr = if (id != 0L && feature(P.F_PROMPTS)) Prompts.load(prefs).firstOrNull { it.id == id } else null
        if (pr == null) {
            if (lives.remove(PROMPT_KEY) != null && expandedKey == PROMPT_KEY) expandedKey = null
            return
        }
        val item = lives[PROMPT_KEY] ?: LiveItem(PROMPT_KEY, LiveKind.PROMPT).also { lives[PROMPT_KEY] = it }
        item.title = pr.text
        item.emoji = pr.emoji
    }

    // ------------------------------------------------------------------ ticking

    private val ticker = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val m = lives[MEDIA_KEY]
            if (m != null && m.media?.playing != true && mediaPausedAt > 0L &&
                now - mediaPausedAt > 90_000L && expandedKey != MEDIA_KEY
            ) {
                lives.remove(MEDIA_KEY)
                render()
            }
            val ek = expandedKey
            if (ek != null && now - lastTouch > 15_000L && lives[ek]?.incoming != true) collapse()
            if (previewUntil != 0L && now > previewUntil) {
                previewUntil = 0L
                render()
            }
            panel?.tick()
            checkPrompts()
            // Pick up the "Display over other apps" permission if it was just granted (or removed).
            if (++probeCheck >= 3) {
                probeCheck = 0
                if (islandView != null) updateProbe()
            }
            // Re-attach the notification listener if Android dropped it.
            if (++rebindCheck >= 30) {
                rebindCheck = 0
                if (NotifListener.instance == null) {
                    try {
                        android.service.notification.NotificationListenerService.requestRebind(
                            android.content.ComponentName(this@IslandService, NotifListener::class.java)
                        )
                    } catch (e: Exception) {
                    }
                }
            }
            main.postDelayed(this, 1000L)
        }
    }

    private val updateCheck = object : Runnable {
        override fun run() {
            Updater.backgroundCheck(this@IslandService)
            main.postDelayed(this, 6L * 60L * 60L * 1000L)
        }
    }

    private fun checkPrompts() {
        val cal = Calendar.getInstance()
        val hhmm = String.format(Locale.US, "%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
        if (hhmm == lastMinute) return
        lastMinute = hhmm
        if (!feature(P.F_PROMPTS)) return
        val today = String.format(
            Locale.US, "%04d-%02d-%02d",
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH)
        )
        val list = Prompts.load(prefs)
        var changed = false
        for (p in list) {
            if (p.time == hhmm && p.lastShown != today) {
                p.lastShown = today
                changed = true
                showPrompt(p.emoji, p.text)
            }
        }
        if (changed) Prompts.save(prefs, list)
    }

    // ------------------------------------------------------------------ system events

    fun feature(key: String): Boolean = prefs.getBoolean(key, true)

    private val systemReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val i = intent ?: return
            when (i.action) {
                Intent.ACTION_POWER_CONNECTED ->
                    if (feature(P.F_CHARGING)) main.postDelayed({ chargingToast() }, 400L)
                Intent.ACTION_BATTERY_CHANGED -> onBattery(i)
                AudioManager.RINGER_MODE_CHANGED_ACTION ->
                    onRinger(i.getIntExtra(AudioManager.EXTRA_RINGER_MODE, -1))
                BluetoothDevice.ACTION_ACL_CONNECTED -> onBluetooth(i)
            }
        }
    }

    private val promptReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val i = intent ?: return
            val text = i.getStringExtra("text") ?: return
            showPrompt(i.getStringExtra("emoji") ?: "", text, i.getLongExtra("duration", 5000L))
        }
    }

    private fun registerReceivers() {
        val f = IntentFilter()
        f.addAction(Intent.ACTION_POWER_CONNECTED)
        f.addAction(Intent.ACTION_BATTERY_CHANGED)
        f.addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
        f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
        val pf = IntentFilter(ACTION_PROMPT)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(systemReceiver, f, Context.RECEIVER_EXPORTED)
            registerReceiver(promptReceiver, pf, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(systemReceiver, f)
            registerReceiver(promptReceiver, pf)
        }
        receiversOn = true
    }

    private fun batteryLevel(): Int = try {
        (getSystemService(BATTERY_SERVICE) as BatteryManager).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    } catch (e: Exception) {
        -1
    }

    private fun chargingToast() {
        val lvl = batteryLevel().coerceIn(0, 100)
        showToast(
            ToastItem(
                ToastStyle.BANNER, title = "Charging", value = "$lvl%",
                icon = tinted(R.drawable.ic_bolt, C.GREEN), accent = C.GREEN,
                battery = lvl, charging = true, duration = 3200L
            )
        )
    }

    private fun onBattery(i: Intent) {
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level < 0) return
        val pct = level * 100 / max(scale, 1)
        val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val prev = lastBattery
        lastBattery = pct
        if (prev < 0 || prev == pct) return
        if (!plugged && prev > 20 && pct <= 20 && feature(P.F_LOWBATTERY)) {
            showToast(
                ToastItem(
                    ToastStyle.BANNER, title = "Low Battery", value = "$pct%",
                    accent = C.RED, battery = pct, duration = 3500L
                )
            )
        }
        if (plugged && prev in 1..99 && pct == 100 && feature(P.F_CHARGING)) {
            showToast(
                ToastItem(
                    ToastStyle.BANNER, title = "Fully Charged", value = "100%",
                    icon = tinted(R.drawable.ic_bolt, C.GREEN), accent = C.GREEN,
                    battery = 100, duration = 3000L
                )
            )
        }
    }

    private fun onRinger(mode: Int) {
        if (mode < 0 || mode == lastRinger) return
        val prev = lastRinger
        lastRinger = mode
        if (prev < 0 || !feature(P.F_RINGER)) return
        ringerToast(mode)
    }

    private fun ringerToast(mode: Int) {
        val t = when (mode) {
            AudioManager.RINGER_MODE_SILENT -> ToastItem(
                ToastStyle.BANNER, title = "Silent", value = "On",
                icon = tinted(R.drawable.ic_bell_off, C.RED), accent = C.RED, duration = 2400L
            )
            AudioManager.RINGER_MODE_VIBRATE -> ToastItem(
                ToastStyle.BANNER, title = "Vibrate", value = "On",
                icon = tinted(R.drawable.ic_vibrate, C.ORANGE), accent = C.ORANGE, duration = 2400L
            )
            else -> ToastItem(
                ToastStyle.BANNER, title = "Ring", value = "On",
                icon = tinted(R.drawable.ic_bell, android.graphics.Color.WHITE), duration = 2400L
            )
        }
        showToast(t)
    }

    @Suppress("DEPRECATION")
    private fun onBluetooth(i: Intent) {
        if (!feature(P.F_BLUETOOTH)) return
        val d: BluetoothDevice = (
            if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            else i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            ) ?: return
        try {
            val major = d.bluetoothClass?.majorDeviceClass
            if (major == BluetoothClass.Device.Major.COMPUTER || major == BluetoothClass.Device.Major.PHONE) return
            val name = d.name ?: "Bluetooth"
            main.postDelayed({
                val b = btBattery(d)
                showToast(
                    ToastItem(
                        ToastStyle.BANNER, title = name.take(18),
                        value = if (b >= 0) "$b%" else "Connected",
                        icon = tinted(R.drawable.ic_headset, C.BLUE), accent = if (b in 0..20) C.RED else C.GREEN,
                        battery = b, duration = 3500L
                    )
                )
            }, 1500L)
        } catch (e: SecurityException) {
        }
    }

    private fun btBattery(d: BluetoothDevice): Int = try {
        d.javaClass.getMethod("getBatteryLevel").invoke(d) as Int
    } catch (e: Throwable) {
        -1
    }

    private fun registerTorch() {
        try {
            val cm = getSystemService(CAMERA_SERVICE) as CameraManager
            torchReadyAt = SystemClock.elapsedRealtime() + 1500L
            val cb = object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                    if (SystemClock.elapsedRealtime() < torchReadyAt) {
                        torchOn = enabled
                        return
                    }
                    if (enabled == torchOn) return
                    torchOn = enabled
                    if (!feature(P.F_FLASHLIGHT)) return
                    val color = if (enabled) C.YELLOW else C.GRAY
                    showToast(
                        ToastItem(
                            ToastStyle.BANNER, title = "Flashlight", value = if (enabled) "On" else "Off",
                            icon = tinted(R.drawable.ic_flashlight, color), accent = color, duration = 2200L
                        )
                    )
                }
            }
            cm.registerTorchCallback(cb, main)
            torchCallback = cb
        } catch (e: Exception) {
        }
    }

    // ------------------------------------------------------------------ helpers

    fun appIcon(pkg: String): Drawable? = try {
        packageManager.getApplicationIcon(pkg)
    } catch (e: Exception) {
        null
    }

    fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

    /** "Try it" buttons in the app. */
    fun demo(kind: String) {
        when (kind) {
            "notification" -> showToast(
                ToastItem(
                    ToastStyle.CARD, title = "Mum", text = "Are you coming for dinner tonight? 🍝",
                    icon = getDrawable(R.mipmap.ic_launcher), appName = "Messages", duration = toastMs()
                )
            )
            "charging" -> chargingToast()
            "silent" -> ringerToast(AudioManager.RINGER_MODE_SILENT)
            "bluetooth" -> showToast(
                ToastItem(
                    ToastStyle.BANNER, title = "Headphones", value = "84%",
                    icon = tinted(R.drawable.ic_headset, C.BLUE), accent = C.GREEN, battery = 84, duration = 3500L
                )
            )
            "prompt" -> showPrompt("✨", "This is your Dynamic Island!")
            "timer" -> {
                upsertLive(DEMO_TIMER, LiveKind.TIMER) { item ->
                    item.hasChrono = true
                    item.countDown = true
                    item.chronoBase = System.currentTimeMillis() + 60_000L
                    item.title = "Timer"
                    item.text = "1 minute"
                    item.appName = "Clock"
                    item.pkg = packageName
                }
                main.postDelayed({ removeLive(DEMO_TIMER) }, 61_000L)
            }
        }
    }
}
