package com.moongames.dynamicisland

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

@Suppress("DEPRECATION")
class MainActivity : Activity(), SharedPreferences.OnSharedPreferenceChangeListener {

    companion object {
        const val EXTRA_UPDATE = "do_update"

        @Volatile
        var visible = false
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var root: LinearLayout
    private val refreshers = mutableListOf<() -> Unit>()
    private lateinit var updateStatus: TextView
    private lateinit var updateNotes: TextView
    private lateinit var updateBar: ProgressBar
    private lateinit var updateButton: TextView
    private lateinit var promptList: LinearLayout
    private lateinit var preview: IslandPreview
    private var latest: Updater.Release? = null
    private var lastCheck = 0L
    private var pickedTime = ""
    private var statusTicker: Runnable? = null

    /** Material You: the phone's own accent colour on Android 12+, iOS blue before that. */
    private val accent: Int by lazy {
        if (Build.VERSION.SDK_INT >= 31) {
            try { getColor(android.R.color.system_accent1_400) } catch (e: Exception) { C.BLUE }
        } else C.BLUE
    }
    private val repoUrl: String get() = "https://github.com/" + BuildConfig.GITHUB_REPO

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs.get(this)
        Prefs.seed(prefs)
        Updater.createChannel(this)

        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Color.BLACK)
        scroll.isVerticalScrollBarEnabled = false
        root = vbox()
        root.setPadding(16.dp, 48.dp, 16.dp, 40.dp)
        scroll.addView(root, ViewGroup.LayoutParams(MATCH, WRAP))
        setContentView(scroll)
        scroll.setOnApplyWindowInsetsListener { _, insets ->
            val top: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= 30) {
                val i = insets.getInsets(WindowInsets.Type.systemBars())
                top = i.top
                bottom = i.bottom
            } else {
                top = insets.systemWindowInsetTop
                bottom = insets.systemWindowInsetBottom
            }
            root.setPadding(16.dp, top + 12.dp, 16.dp, bottom + 40.dp)
            insets
        }

        build()
        handleIntent(intent)
        prefs.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onDestroy() {
        try { prefs.unregisterOnSharedPreferenceChangeListener(this) } catch (e: Exception) { }
        super.onDestroy()
    }

    override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
        if (key == P.WIDTH || key == P.HEIGHT || key == P.OFF_X || key == P.OFF_Y) preview.invalidate()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        visible = true
        // Android sometimes drops the notification listener (e.g. after an update); ask it back.
        if (listenerOn() && NotifListener.instance == null) requestListenerRebind(this)
        refreshers.forEach { it() }
        statusTicker?.let {
            root.removeCallbacks(it)
            root.postDelayed(it, 1500L)
        }
        if (System.currentTimeMillis() - lastCheck > 10 * 60 * 1000L) checkForUpdates(false)
    }

    override fun onPause() {
        visible = false
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshers.forEach { it() }
    }

    private fun handleIntent(i: Intent?) {
        if (i?.getBooleanExtra(EXTRA_UPDATE, false) == true) {
            i.removeExtra(EXTRA_UPDATE)
            checkForUpdates(true, installIfFound = true)
        }
    }

    // ------------------------------------------------------------------ screen

    private fun build() {
        buildHero()
        buildSetup()
        buildTry()
        buildApps()
        buildFeatures()
        buildLook()
        buildPrompts()
        buildStatusBar()
        buildUpdates()
        buildDiagnostics()
        buildAbout()
    }

    /** Title, live preview, status chip and the main on/off switch. */
    private fun buildHero() {
        val head = hbox()
        head.setPadding(4.dp, 6.dp, 0, 0)
        head.addView(label("Dynamic Island", 30f, Color.WHITE, bold = true), llp(0, WRAP, 1f))
        val sw = Switch(this)
        tintSwitch(sw)
        sw.isChecked = prefs.getBoolean(P.ENABLED, true)
        sw.setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(P.ENABLED, on).apply() }
        head.addView(sw, llp(WRAP, WRAP))
        root.addView(head, llp(MATCH, WRAP))

        preview = IslandPreview(this, accent)
        preview.roundCorners(26f.dpf)
        preview.setOnClickListener {
            preview.busy = !preview.busy
            if (preview.busy) demo("notification")
        }
        root.addView(preview, llp(MATCH, 150.dp).apply { topMargin = 14.dp })

        val chipRow = hbox()
        chipRow.setPadding(2.dp, 12.dp, 2.dp, 0)
        val chip = label("", 13f, Color.WHITE, bold = true, lines = 3)
        chip.setPadding(12.dp, 7.dp, 12.dp, 7.dp)
        chipRow.addView(chip, llp(WRAP, WRAP))
        root.addView(chipRow, llp(MATCH, WRAP))
        val paintChip = {
            val s = IslandService.instance
            val text = when {
                s != null -> s.statusText()
                a11yOn() -> "Switched on, but not running — turn the Island overlay off and on again"
                else -> "Not running — turn on Island overlay in Setup"
            }
            val good = text.startsWith("Running ✓")
            chip.text = "●  $text"
            chip.background = roundRect(if (good) 0x2630D158 else 0x26FF9F0A, 50f.dpf)
            chip.setTextColor(if (good) C.GREEN else C.ORANGE)
        }
        refreshers += paintChip
        statusTicker = Runnable {
            paintChip()
            if (visible) statusTicker?.let { root.postDelayed(it, 1500L) }
        }
        root.addView(label(
            "Tap the island to open · hold to expand · swipe sideways to tuck away · hold when idle for quick apps",
            12.5f, C.GRAY, lines = 3
        ).apply { setPadding(4.dp, 10.dp, 4.dp, 0) })
    }

    private fun setupSteps(): List<Pair<String, () -> Boolean>> {
        val steps = mutableListOf<Pair<String, () -> Boolean>>(
            "overlay" to { a11yOn() },
            "notifications" to { listenerOn() },
            "calls" to { granted(Manifest.permission.READ_PHONE_STATE) && granted(Manifest.permission.ANSWER_PHONE_CALLS) },
            "battery" to { (getSystemService(POWER_SERVICE) as android.os.PowerManager).isIgnoringBatteryOptimizations(packageName) },
            "install" to { packageManager.canRequestPackageInstalls() }
        )
        if (Build.VERSION.SDK_INT >= 33) steps += "alerts" to { granted(Manifest.permission.POST_NOTIFICATIONS) }
        if (Build.VERSION.SDK_INT >= 31) steps += "bluetooth" to { granted(Manifest.permission.BLUETOOTH_CONNECT) }
        return steps
    }

    private fun buildSetup() {
        val steps = setupSteps()
        val essentialsDone = a11yOn() && listenerOn()
        val c = section("Setup", key = "setup", defaultOpen = !essentialsDone, forceOpen = !essentialsDone, badge = {
            val done = steps.count { it.second() }
            if (done == steps.size) "All done" else "$done of ${steps.size} done"
        })
        statusRow(c, "Island overlay", "Accessibility service that draws the island over the status bar.", { a11yOn() }, "Turn on") {
            toast("Open \"Dynamic Island\" and switch it on")
            open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        val help = vbox()
        help.addView(label("Switch greyed out? Android blocks it for apps not from the Play Store. Open App info → ⋮ (top right) → Allow restricted settings, then try again.", 13f, C.GRAY, lines = 5))
        val infoBtn = button("App info", C.CARD2) {
            open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        help.addView(infoBtn, llp(WRAP, WRAP).apply { topMargin = 8.dp })
        help.setPadding(0, 0, 0, 12.dp)
        c.addView(help)
        refreshers += { help.visibility = if (a11yOn()) View.GONE else View.VISIBLE }

        divider(c)
        statusRow(c, "Notification access", "For notifications, music, calls, timers, alarms, Focus and maps.", { listenerOn() }, "Allow") {
            open(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        }
        divider(c)
        statusRow(
            c, "Calls",
            "Shows every call in the island and makes Answer / Hang up work. Doesn't read numbers or call history.",
            { granted(Manifest.permission.READ_PHONE_STATE) && granted(Manifest.permission.ANSWER_PHONE_CALLS) }, "Allow"
        ) {
            requestPermissions(arrayOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.ANSWER_PHONE_CALLS), 3)
        }
        divider(c)
        statusRow(
            c, "Keep running",
            "Stops Samsung's battery saver from closing the island in the background.",
            { (getSystemService(POWER_SERVICE) as android.os.PowerManager).isIgnoringBatteryOptimizations(packageName) }, "Allow"
        ) {
            @android.annotation.SuppressLint("BatteryLife")
            val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            open(i)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            divider(c)
            statusRow(c, "Update & timer alerts", "Lets the app tell you when a new version is ready, and ring when a timer ends.", { granted(Manifest.permission.POST_NOTIFICATIONS) }, "Allow") {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }
        if (Build.VERSION.SDK_INT >= 31) {
            divider(c)
            statusRow(c, "Headphone pop-ups", "Shows Bluetooth headphones connecting, with battery.", { granted(Manifest.permission.BLUETOOTH_CONNECT) }, "Allow") {
                requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 2)
            }
        }
        divider(c)
        statusRow(c, "Install updates", "Lets the app install new versions from GitHub.", { packageManager.canRequestPackageInstalls() }, "Allow") {
            open(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
        }
        divider(c)
        statusRow(c, "Hide during videos & games", "Optional. \"Display over other apps\" lets the island tell when an app goes full-screen.", { Settings.canDrawOverlays(this) }, "Allow") {
            open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
    }

    private fun buildTry() {
        val c = section("Try it", key = "try")
        val rows = listOf(
            listOf("Notification" to "notification", "Charging" to "charging", "Silent" to "silent"),
            listOf("Timer" to "timer", "Prompt" to "prompt", "Headphones" to "bluetooth"),
            listOf("Call" to "call", "Unlock" to "unlock", "Quick apps" to "quick"),
            listOf("Focus" to "dnd", "Volume" to "volume", "Alarm" to "alarm")
        )
        for ((ri, r) in rows.withIndex()) {
            val row = hbox()
            row.setPadding(0, if (ri == 0) 12.dp else 8.dp, 0, if (ri == rows.size - 1) 14.dp else 0)
            r.forEachIndexed { i, (labelText, key) ->
                val b = button(labelText, C.CARD2) { demo(key) }
                row.addView(b, llp(0, WRAP, 1f).apply { if (i > 0) marginStart = 8.dp })
            }
            c.addView(row)
        }
    }

    private fun buildStatusBar() {
        val c = section("Status bar icons", key = "icons", defaultOpen = false)
        val sw = Switch(this)
        tintSwitch(sw)
        sw.isChecked = prefs.getBoolean(P.NATIVE_ICONS, false) && StatusIcons.canWrite(this)
        sw.setOnCheckedChangeListener { b, on ->
            if (on && !StatusIcons.canWrite(this)) {
                b.isChecked = false
                showAdbHelp()
                return@setOnCheckedChangeListener
            }
            prefs.edit().putBoolean(P.NATIVE_ICONS, on).apply()
            StatusIcons.apply(this, on)
        }
        row(
            c, "Hide system icons",
            "Removes Android's own Wi-Fi, signal, battery and sound icons (the time stays, nothing is covered). Needs a one-time command from a computer.",
            sw
        )
    }

    private fun buildApps() {
        val c = section("Apps & notifications", key = "apps")
        val count = label("", 13f, C.GRAY, lines = 2)
        val chooseBtn = button("Choose", accent) { open(Intent(this, AppsActivity::class.java)) }
        val v = vbox()
        v.setPadding(0, 12.dp, 0, 12.dp)
        val h = hbox()
        val t = vbox()
        t.addView(label("Choose apps", 16f, Color.WHITE))
        count.setPadding(0, 4.dp, 0, 0)
        t.addView(count)
        h.addView(t, llp(0, WRAP, 1f))
        h.addView(chooseBtn, llp(WRAP, WRAP).apply { marginStart = 12.dp })
        v.addView(h)
        c.addView(v)
        refreshers += {
            val off = (prefs.getStringSet(P.BLOCKED_APPS, emptySet()) ?: emptySet()).size
            count.text = (if (off == 0) "Every app can use the island" else "$off app${if (off == 1) "" else "s"} switched off") +
                " · pick your quick apps"
        }
        divider(c)
        switchRow(
            c, "Hide message text when locked",
            "While the phone is locked, pop-ups show only the app name (like iPhone).", P.HIDE_LOCKED, true
        )
        divider(c)
        switchRow(
            c, "Swipe away clears it",
            "Swiping a notification off the island also clears it from the notification shade.", P.SWIPE_CLEARS, true
        )
        divider(c)
        sliderRow(c, "Pop-up time", P.TOAST_SECS, 2, 10, 4, " s")
    }

    private fun buildFeatures() {
        val c = section("What the island shows", key = "features")
        P.FEATURES.forEachIndexed { i, f ->
            if (i > 0) divider(c)
            switchRow(c, f.label, f.desc, f.key, true)
        }
    }

    private fun buildLook() {
        val c = section("Look & feel", key = "look")
        c.addView(label("Drag the sliders and watch the preview at the top.", 13f, C.GRAY, lines = 2).apply {
            setPadding(0, 12.dp, 0, 0)
        })
        sliderRow(c, "Width", P.WIDTH, 50, 220, AutoSize.widthDp(this), " dp")
        divider(c)
        sliderRow(c, "Height", P.HEIGHT, 18, 50, AutoSize.heightDp(this), " dp")
        divider(c)
        sliderRow(c, "Move up / down", P.OFF_Y, -30, 60, 0, "")
        divider(c)
        sliderRow(c, "Move left / right", P.OFF_X, -160, 160, 0, "")
        divider(c)
        val seg = hbox()
        val names = listOf("Slow", "Normal", "Fast")
        val btns = names.map { button(it, C.CARD2) {} }
        fun paint() {
            val cur = prefs.getInt(P.ANIM_SPEED, 1)
            btns.forEachIndexed { i, b -> b.background = roundRect(if (i == cur) accent else C.CARD2, 50f.dpf) }
        }
        btns.forEachIndexed { i, b ->
            b.setOnClickListener {
                prefs.edit().putInt(P.ANIM_SPEED, i).apply()
                paint()
                IslandService.instance?.demo("unlock")
            }
            b.setPadding(12.dp, 9.dp, 12.dp, 9.dp)
            seg.addView(b, llp(WRAP, WRAP).apply { if (i > 0) marginStart = 6.dp })
        }
        paint()
        val animRow = vbox()
        animRow.setPadding(0, 12.dp, 0, 12.dp)
        animRow.addView(label("Animation speed", 16f, Color.WHITE))
        animRow.addView(seg, llp(WRAP, WRAP).apply { topMargin = 10.dp })
        c.addView(animRow)
        divider(c)
        switchRow(c, "Haptic tick", "A soft tap you feel when something pops out of the island.", P.HAPTICS, true)
        divider(c)
        switchRow(c, "Show pill when idle", "Keep the black pill around the camera even when nothing is happening.", P.IDLE_VISIBLE, true)
        divider(c)
        switchRow(c, "Hide in full-screen apps", "Gets out of the way of videos and games.", P.HIDE_FULLSCREEN, true)
        divider(c)
        val btnRow = hbox()
        btnRow.setPadding(0, 12.dp, 0, 12.dp)
        btnRow.addView(button("iPhone size", C.CARD2) {
            prefs.edit().putInt(P.WIDTH, 126).putInt(P.HEIGHT, 37).apply()
            recreate()
        })
        btnRow.addView(button("Reset", C.CARD2) {
            prefs.edit().remove(P.WIDTH).remove(P.HEIGHT).remove(P.OFF_X).remove(P.OFF_Y).apply()
            recreate()
        }, llp(WRAP, WRAP).apply { marginStart = 8.dp })
        c.addView(btnRow)
    }

    private fun buildPrompts() {
        val c = section("Custom prompts", key = "prompts")
        c.addView(label("Show your own messages in the island — right now, pinned so they stay, or every day at a set time.", 13f, C.GRAY, lines = 4).apply {
            setPadding(0, 12.dp, 0, 10.dp)
        })

        val form = hbox()
        val emoji = edit("✨", 1)
        emoji.gravity = Gravity.CENTER
        form.addView(emoji, llp(54.dp, 46.dp))
        val text = edit("Type a message…", 2)
        form.addView(text, llp(0, 46.dp, 1f).apply { marginStart = 8.dp })
        c.addView(form)

        val actions = hbox()
        actions.setPadding(0, 8.dp, 0, 4.dp)
        lateinit var timeBtn: TextView
        timeBtn = button("No time", C.CARD2) {
            TimePickerDialog(this, { _, h, m ->
                pickedTime = String.format(Locale.US, "%02d:%02d", h, m)
                timeBtn.text = "Daily $pickedTime"
            }, 8, 0, DateFormat.is24HourFormat(this)).show()
        }
        timeBtn.setOnLongClickListener {
            pickedTime = ""
            timeBtn.text = "No time"
            true
        }
        actions.addView(timeBtn)
        val add = button("Add prompt", accent) {
            val t = text.text.toString().trim()
            if (t.isEmpty()) {
                toast("Type a message first")
                return@button
            }
            val list = Prompts.load(prefs)
            list.add(Prompt(System.currentTimeMillis(), t, emoji.text.toString().trim(), pickedTime, ""))
            Prompts.save(prefs, list)
            text.setText("")
            emoji.setText("")
            pickedTime = ""
            timeBtn.text = "No time"
            renderPrompts()
        }
        actions.addView(add, llp(WRAP, WRAP).apply { marginStart = 8.dp })
        c.addView(actions)

        promptList = vbox()
        c.addView(promptList)
        renderPrompts()

        c.addView(label("Apps like Tasker can send prompts too: broadcast ${IslandService.ACTION_PROMPT} with a \"text\" extra (and optional \"emoji\").", 12f, C.GRAY, lines = 5).apply {
            setPadding(0, 6.dp, 0, 14.dp)
            setTextIsSelectable(true)
        })
    }

    private fun renderPrompts() {
        promptList.removeAllViews()
        val list = Prompts.load(prefs)
        val pinned = prefs.getLong(P.PINNED, 0L)
        if (list.isEmpty()) {
            promptList.addView(label("No prompts yet.", 14f, C.GRAY).apply { setPadding(0, 10.dp, 0, 10.dp) })
            return
        }
        for (p in list) {
            val line = View(this)
            line.setBackgroundColor(C.LINE)
            promptList.addView(line, llp(MATCH, 1).apply { topMargin = 8.dp })
            val v = vbox()
            v.setPadding(0, 10.dp, 0, 4.dp)
            v.addView(label("${p.emoji} ${p.text}".trim(), 15.5f, Color.WHITE, lines = 3))
            v.addView(label(if (p.time.isEmpty()) "Manual" else "Every day at ${p.time}", 12.5f, C.GRAY).apply {
                setPadding(0, 3.dp, 0, 8.dp)
            })
            val btns = hbox()
            btns.addView(button("Show", accent) {
                val s = IslandService.instance
                if (s == null) toast("Turn on the island overlay first (Setup)") else s.showPrompt(p.emoji, p.text)
            })
            btns.addView(button(if (pinned == p.id) "Unpin" else "Pin", C.CARD2) {
                if (pinned == p.id) prefs.edit().remove(P.PINNED).apply()
                else prefs.edit().putLong(P.PINNED, p.id).apply()
                renderPrompts()
            }, llp(WRAP, WRAP).apply { marginStart = 8.dp })
            btns.addView(button("Delete", C.CARD2) {
                val all = Prompts.load(prefs)
                all.removeAll { it.id == p.id }
                Prompts.save(prefs, all)
                if (pinned == p.id) prefs.edit().remove(P.PINNED).apply()
                renderPrompts()
            }, llp(WRAP, WRAP).apply { marginStart = 8.dp })
            v.addView(btns)
            promptList.addView(v)
        }
    }

    private fun buildUpdates() {
        val c = section("Updates", key = "updates", badge = { "v${BuildConfig.VERSION_NAME}" })
        updateStatus = label("Version ${BuildConfig.VERSION_NAME}", 15.5f, Color.WHITE, lines = 3)
        updateStatus.setPadding(0, 14.dp, 0, 0)
        c.addView(updateStatus)
        updateNotes = label("", 13f, C.GRAY, lines = 8)
        updateNotes.setPadding(0, 6.dp, 0, 0)
        updateNotes.visibility = View.GONE
        c.addView(updateNotes)
        updateBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        updateBar.max = 100
        updateBar.progressTintList = ColorStateList.valueOf(accent)
        updateBar.visibility = View.GONE
        c.addView(updateBar, llp(MATCH, WRAP).apply { topMargin = 8.dp })
        updateButton = button("Check for updates", accent) {
            val l = latest
            if (l != null && l.versionCode > BuildConfig.VERSION_CODE) startInstall(l) else checkForUpdates(true)
        }
        c.addView(updateButton, llp(WRAP, WRAP).apply { topMargin = 12.dp; bottomMargin = 14.dp })
        divider(c)
        switchRow(
            c, "Install updates automatically",
            "Checks GitHub every 6 hours. New versions download in the background; Android may ask you to tap Install.",
            P.AUTO_UPDATE, true
        )
    }

    private fun buildDiagnostics() {
        val c = section("Diagnostics", key = "diag", defaultOpen = false)
        c.addView(label(
            "What the island has seen recently (app names and types only, never message text). If something doesn't show, send a screenshot of this.",
            13f, C.GRAY, lines = 4
        ).apply { setPadding(0, 12.dp, 0, 8.dp) })
        val log = TextView(this)
        log.textSize = 11.5f
        log.setTextColor(C.LIGHT)
        log.typeface = android.graphics.Typeface.MONOSPACE
        log.setTextIsSelectable(true)
        c.addView(log, llp(MATCH, WRAP))
        val row = hbox()
        row.setPadding(0, 10.dp, 0, 14.dp)
        row.addView(button("Refresh", C.CARD2) { log.text = Diag.dump() })
        row.addView(button("Copy", C.CARD2) {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(
                ClipData.newPlainText("Dynamic Island diagnostics", "v${BuildConfig.VERSION_NAME}\n" + Diag.dump())
            )
            toast("Copied")
        }, llp(WRAP, WRAP).apply { marginStart = 8.dp })
        c.addView(row)
        refreshers += { log.text = Diag.dump() }
    }

    private fun buildAbout() {
        val c = section("About", key = "about", defaultOpen = false)
        val link = label(repoUrl.removePrefix("https://"), 14f, accent, lines = 2)
        link.setPadding(0, 14.dp, 0, 6.dp)
        link.setOnClickListener { open(Intent(Intent.ACTION_VIEW, Uri.parse(repoUrl))) }
        c.addView(link)
        c.addView(label(
            "Ideas borrowed from dynamicSpot, SmartIsland, Expressive Cutout and Material You Dynamic Island. Built with no ads, no tracking and no internet use except checking GitHub for updates.",
            12.5f, C.GRAY, lines = 5
        ).apply { setPadding(0, 0, 0, 14.dp) })
    }

    // ------------------------------------------------------------------ updates

    private fun checkForUpdates(userAsked: Boolean, installIfFound: Boolean = false) {
        lastCheck = System.currentTimeMillis()
        if (userAsked) updateStatus.text = "Checking GitHub…"
        Updater.fetchAsync { rel, err ->
            if (isFinishing) return@fetchAsync
            if (rel == null) {
                updateStatus.text = if (userAsked) "Couldn't check: $err" else "Version ${BuildConfig.VERSION_NAME}"
                return@fetchAsync
            }
            latest = rel
            if (rel.versionCode > BuildConfig.VERSION_CODE) {
                updateStatus.text = "New version available: ${rel.name}"
                if (rel.notes.isNotBlank()) {
                    updateNotes.text = rel.notes.take(500)
                    updateNotes.visibility = View.VISIBLE
                }
                updateButton.text = "Install update"
                if (installIfFound) {
                    startInstall(rel)
                } else if (!userAsked) {
                    AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle("Update available")
                        .setMessage("${rel.name} is ready.\n\n${rel.notes.take(300)}")
                        .setPositiveButton("Install") { _, _ -> startInstall(rel) }
                        .setNegativeButton("Later", null)
                        .show()
                }
            } else {
                updateStatus.text = "You're on the latest version (${BuildConfig.VERSION_NAME})"
                updateNotes.visibility = View.GONE
                updateButton.text = "Check for updates"
            }
        }
    }

    private fun startInstall(rel: Updater.Release) {
        if (!packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Allow updates")
                .setMessage("To install the new version, let Dynamic Island install apps. Switch it on, then come back and tap Install update.")
                .setPositiveButton("Open settings") { _, _ ->
                    open(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                }
                .setNegativeButton("Not now", null)
                .show()
            return
        }
        updateBar.visibility = View.VISIBLE
        updateBar.progress = 0
        updateButton.isEnabled = false
        updateStatus.text = "Downloading…"
        Updater.downloadAsync(this, rel, { p ->
            updateBar.progress = p
            updateStatus.text = "Downloading… $p%"
        }) { file, err ->
            updateButton.isEnabled = true
            if (file == null) {
                updateBar.visibility = View.GONE
                updateStatus.text = "Download failed: $err"
                return@downloadAsync
            }
            updateStatus.text = "Installing…"
            Updater.installAsync(this, file) { e ->
                updateBar.visibility = View.GONE
                updateStatus.text = if (e == null) "Installing — confirm if Android asks." else "Install failed: $e"
            }
        }
    }

    private fun showAdbHelp() {
        val cmd = "adb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS"
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("One-time setup")
            .setMessage(
                "1. On this phone, turn on Developer options → USB debugging.\n" +
                    "2. Plug it into a computer with Android platform-tools (ADB).\n" +
                    "3. Run:\n\n$cmd\n\n4. Come back and switch Hide system icons on."
            )
            .setPositiveButton("Copy command") { _, _ ->
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("adb", cmd))
                toast("Copied")
            }
            .setNegativeButton("Close", null)
            .show()
    }

    // ------------------------------------------------------------------ helpers

    private fun demo(kind: String) {
        val s = IslandService.instance
        if (s == null) toast("Turn on the island overlay first (Setup)") else s.demo(kind)
    }

    private fun a11yOn(): Boolean {
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return s.split(':').any { ComponentName.unflattenFromString(it)?.packageName == packageName }
    }

    private fun listenerOn(): Boolean {
        val s = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return s.split(':').any { ComponentName.unflattenFromString(it)?.packageName == packageName }
    }

    private fun granted(perm: String): Boolean = checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    private fun open(i: Intent) {
        try {
            startActivity(i)
        } catch (e: Exception) {
            toast("Couldn't open that screen")
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun tintSwitch(sw: Switch) {
        try {
            val on = accent
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            sw.thumbTintList = ColorStateList(states, intArrayOf(Color.WHITE, 0xFFE5E5EA.toInt()))
            sw.trackTintList = ColorStateList(states, intArrayOf(on, 0xFF48484A.toInt()))
        } catch (e: Exception) {
        }
    }

    /**
     * A card with a tappable header. Tap the title to fold the card away; the choice is remembered.
     * [badge] is a short note on the right of the title ("3 of 7 done", "v1.8").
     */
    private fun section(
        title: String, key: String, defaultOpen: Boolean = true, forceOpen: Boolean = false, badge: (() -> String)? = null
    ): LinearLayout {
        val prefKey = "sec_$key"
        var open = forceOpen || prefs.getBoolean(prefKey, defaultOpen)

        val header = hbox()
        header.setPadding(8.dp, 24.dp, 6.dp, 8.dp)
        header.addView(label(title.uppercase(Locale.getDefault()), 13f, C.GRAY, bold = true), llp(0, WRAP, 1f))
        val badgeView = label("", 12f, accent, bold = true)
        badgeView.setPadding(0, 0, 8.dp, 0)
        header.addView(badgeView, llp(WRAP, WRAP))
        val chevron = label("▾", 14f, C.GRAY, bold = true)
        header.addView(chevron, llp(WRAP, WRAP))
        root.addView(header, llp(MATCH, WRAP))

        val card = vbox()
        card.background = roundRect(C.CARD, 22f.dpf)
        card.setPadding(16.dp, 0, 16.dp, 0)
        root.addView(card, llp(MATCH, WRAP))

        fun apply() {
            card.visibility = if (open) View.VISIBLE else View.GONE
            chevron.rotation = if (open) 0f else -90f
        }
        apply()
        header.setOnClickListener {
            open = !open
            prefs.edit().putBoolean(prefKey, open).apply()
            apply()
        }
        if (badge != null) {
            val r = { badgeView.text = badge() }
            refreshers += r
            r()
        }
        return card
    }

    private fun divider(card: LinearLayout) {
        val v = View(this)
        v.setBackgroundColor(C.LINE)
        card.addView(v, llp(MATCH, 1))
    }

    private fun button(text: String, color: Int, onClick: () -> Unit): TextView {
        val b = label(text, 14f, Color.WHITE, bold = true)
        b.gravity = Gravity.CENTER
        b.background = roundRect(color, 50f.dpf)
        b.setPadding(16.dp, 10.dp, 16.dp, 10.dp)
        b.setOnClickListener { onClick() }
        return b
    }

    private fun edit(hint: String, kind: Int): EditText {
        val e = EditText(this)
        e.hint = hint
        e.setHintTextColor(C.GRAY)
        e.setTextColor(Color.WHITE)
        e.textSize = 15f
        e.background = roundRect(C.CARD2, 12f.dpf)
        e.setPadding(12.dp, 0, 12.dp, 0)
        e.setSingleLine(true)
        e.inputType = if (kind == 1) InputType.TYPE_CLASS_TEXT else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        return e
    }

    private fun row(card: LinearLayout, title: String, subtitle: String?, right: View) {
        val h = hbox()
        h.setPadding(0, 12.dp, 0, 12.dp)
        val v = vbox()
        v.addView(label(title, 16f, Color.WHITE, lines = 2))
        if (subtitle != null) {
            val s = label(subtitle, 13f, C.GRAY, lines = 5)
            s.setPadding(0, 4.dp, 0, 0)
            v.addView(s)
        }
        h.addView(v, llp(0, WRAP, 1f))
        h.addView(right, llp(WRAP, WRAP).apply { marginStart = 12.dp })
        card.addView(h)
    }

    private fun statusRow(card: LinearLayout, title: String, sub: String, ok: () -> Boolean, action: String, onClick: () -> Unit) {
        val right = FrameLayout(this)
        val okLabel = label("✓", 16f, C.GREEN, bold = true)
        okLabel.gravity = Gravity.CENTER
        okLabel.background = roundRect(0x2630D158, 50f.dpf)
        val btn = button(action, accent, onClick)
        right.addView(okLabel, flp(32.dp, 32.dp, Gravity.CENTER))
        right.addView(btn, flp(WRAP, WRAP, Gravity.CENTER))
        row(card, title, sub, right)
        val r = {
            val good = ok()
            okLabel.visibility = if (good) View.VISIBLE else View.GONE
            btn.visibility = if (good) View.GONE else View.VISIBLE
        }
        refreshers += r
        r()
    }

    private fun switchRow(card: LinearLayout, title: String, sub: String?, key: String, def: Boolean) {
        val sw = Switch(this)
        tintSwitch(sw)
        sw.isChecked = prefs.getBoolean(key, def)
        sw.setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(key, on).apply() }
        row(card, title, sub, sw)
    }

    private fun sliderRow(card: LinearLayout, title: String, key: String, min: Int, max: Int, def: Int, suffix: String) {
        val v = vbox()
        v.setPadding(0, 12.dp, 0, 10.dp)
        val top = hbox()
        top.addView(label(title, 16f, Color.WHITE), llp(0, WRAP, 1f))
        val value = label("${prefs.getInt(key, def)}$suffix", 14f, C.GRAY)
        top.addView(value)
        v.addView(top)
        val sb = SeekBar(this)
        sb.max = max - min
        sb.progress = prefs.getInt(key, def) - min
        sb.progressTintList = ColorStateList.valueOf(accent)
        sb.thumbTintList = ColorStateList.valueOf(Color.WHITE)
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, progress: Int, fromUser: Boolean) {
                val n = progress + min
                value.text = "$n$suffix"
                if (fromUser) prefs.edit().putInt(key, n).apply()
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        v.addView(sb, llp(MATCH, WRAP).apply { topMargin = 8.dp })
        card.addView(v)
    }
}
