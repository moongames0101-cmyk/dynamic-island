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
class MainActivity : Activity() {

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
    private var latest: Updater.Release? = null
    private var lastCheck = 0L
    private var pickedTime = ""

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
        root.setPadding(18.dp, 48.dp, 18.dp, 40.dp)
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
            root.setPadding(18.dp, top + 20.dp, 18.dp, bottom + 40.dp)
            insets
        }

        build()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        visible = true
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
        root.addView(label("Dynamic Island", 34f, Color.WHITE, bold = true))
        val sub = label("Version ${BuildConfig.VERSION_NAME} · tap the island to expand, hold it to open the app", 14f, C.GRAY, lines = 3)
        sub.setPadding(0, 8.dp, 0, 0)
        root.addView(sub)

        buildSetup()
        buildTry()
        buildStatusBar()
        buildFeatures()
        buildLook()
        buildPrompts()
        buildUpdates()
        buildAbout()
    }

    private fun buildSetup() {
        val c = section("Setup")
        val status = label("", 14f, C.GRAY, bold = true, lines = 3)
        status.setPadding(0, 14.dp, 0, 4.dp)
        c.addView(status)
        val paintStatus = {
            val s = IslandService.instance
            val text = when {
                s != null -> s.statusText()
                a11yOn() -> "Switched on, but not running — turn the Island overlay off and on again"
                else -> "Not running — turn on Island overlay below"
            }
            status.text = "Status: $text"
            status.setTextColor(if (text.startsWith("Running ✓")) C.GREEN else C.ORANGE)
        }
        refreshers += paintStatus
        statusTicker = Runnable {
            paintStatus()
            if (visible) statusTicker?.let { root.postDelayed(it, 1500L) }
        }
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
        statusRow(c, "Notification access", "For notifications, music, calls, timers and maps.", { listenerOn() }, "Allow") {
            open(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        }
        if (Build.VERSION.SDK_INT >= 33) {
            divider(c)
            statusRow(c, "Update alerts", "Lets the app tell you when a new version is ready.", { granted(Manifest.permission.POST_NOTIFICATIONS) }, "Allow") {
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

    private var statusTicker: Runnable? = null

    private fun buildTry() {
        val c = section("Try it")
        val rows = listOf(
            listOf("Notification" to "notification", "Charging" to "charging", "Silent" to "silent"),
            listOf("Timer" to "timer", "Prompt" to "prompt", "Headphones" to "bluetooth")
        )
        for ((ri, r) in rows.withIndex()) {
            val row = hbox()
            row.setPadding(0, if (ri == 0) 12.dp else 8.dp, 0, if (ri == rows.size - 1) 12.dp else 0)
            r.forEachIndexed { i, (labelText, key) ->
                val b = button(labelText, C.CARD2) { demo(key) }
                row.addView(b, llp(0, WRAP, 1f).apply { if (i > 0) marginStart = 8.dp })
            }
            c.addView(row)
        }
        divider(c)
        switchRow(c, "Island on", "Turn the whole thing on or off.", P.ENABLED, true)
    }

    private fun buildStatusBar() {
        val c = section("Status bar")
        switchRow(
            c, "Hide status bar icons",
            "Covers the top bar in black and shows only the time, iPhone-style. Swiping down still opens notifications.",
            P.HIDE_ICONS, true
        )
        divider(c)

        val seg = hbox()
        val leftBtn = button("Left", C.CARD2) {}
        val rightBtn = button("Right", C.CARD2) {}
        fun paint() {
            val right = prefs.getBoolean(P.CLOCK_RIGHT, false)
            leftBtn.background = roundRect(if (!right) C.BLUE else C.CARD2, 50f.dpf)
            rightBtn.background = roundRect(if (right) C.BLUE else C.CARD2, 50f.dpf)
        }
        leftBtn.setOnClickListener { prefs.edit().putBoolean(P.CLOCK_RIGHT, false).apply(); paint() }
        rightBtn.setOnClickListener { prefs.edit().putBoolean(P.CLOCK_RIGHT, true).apply(); paint() }
        seg.addView(leftBtn)
        seg.addView(rightBtn, llp(WRAP, WRAP).apply { marginStart = 6.dp })
        paint()
        row(c, "Time position", null, seg)
        divider(c)

        val sw = Switch(this)
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
            c, "Native mode (advanced)",
            "Hides Android's real status bar icons instead of covering them (notification icons stay). Needs a one-time command from a computer.",
            sw
        )
    }

    private fun buildFeatures() {
        val c = section("What the island shows")
        P.FEATURES.forEachIndexed { i, f ->
            if (i > 0) divider(c)
            switchRow(c, f.label, f.desc, f.key, true)
        }
    }

    private fun buildLook() {
        val c = section("Size & position")
        sliderRow(c, "Width", P.WIDTH, 60, 220, 108, " dp")
        divider(c)
        sliderRow(c, "Height", P.HEIGHT, 20, 50, 32, " dp")
        divider(c)
        sliderRow(c, "Move up / down", P.OFF_Y, -30, 60, 0, "")
        divider(c)
        sliderRow(c, "Move left / right", P.OFF_X, -160, 160, 0, "")
        divider(c)
        sliderRow(c, "Pop-up time", P.TOAST_SECS, 2, 10, 4, " s")
        divider(c)
        switchRow(c, "Show pill when idle", "Keep the black pill around the camera even when nothing is happening.", P.IDLE_VISIBLE, true)
        divider(c)
        switchRow(c, "Hide in full-screen apps", "Gets out of the way of videos and games.", P.HIDE_FULLSCREEN, true)
        divider(c)
        val reset = button("Reset size & position", C.CARD2) {
            prefs.edit().remove(P.WIDTH).remove(P.HEIGHT).remove(P.OFF_X).remove(P.OFF_Y).apply()
            recreate()
        }
        c.addView(reset, llp(WRAP, WRAP).apply { topMargin = 12.dp; bottomMargin = 12.dp })
    }

    private fun buildPrompts() {
        val c = section("Custom prompts")
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
        val add = button("Add prompt", C.BLUE) {
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
            btns.addView(button("Show", C.BLUE) {
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
        val c = section("Updates")
        updateStatus = label("Version ${BuildConfig.VERSION_NAME}", 15.5f, Color.WHITE, lines = 3)
        updateStatus.setPadding(0, 14.dp, 0, 0)
        c.addView(updateStatus)
        updateNotes = label("", 13f, C.GRAY, lines = 8)
        updateNotes.setPadding(0, 6.dp, 0, 0)
        updateNotes.visibility = View.GONE
        c.addView(updateNotes)
        updateBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        updateBar.max = 100
        updateBar.progressTintList = ColorStateList.valueOf(C.BLUE)
        updateBar.visibility = View.GONE
        c.addView(updateBar, llp(MATCH, WRAP).apply { topMargin = 8.dp })
        updateButton = button("Check for updates", C.BLUE) {
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

    private fun buildAbout() {
        val c = section("About")
        val link = label(repoUrl.removePrefix("https://"), 14f, C.BLUE, lines = 2)
        link.setPadding(0, 14.dp, 0, 14.dp)
        link.setOnClickListener { open(Intent(Intent.ACTION_VIEW, Uri.parse(repoUrl))) }
        c.addView(link)
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
                    "3. Run:\n\n$cmd\n\n4. Come back and switch Native mode on."
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

    private fun section(title: String): LinearLayout {
        val h = label(title.uppercase(Locale.getDefault()), 13f, C.GRAY, bold = true)
        h.setPadding(6.dp, 26.dp, 0, 8.dp)
        root.addView(h)
        val card = vbox()
        card.background = roundRect(C.CARD, 20f.dpf)
        card.setPadding(16.dp, 0, 16.dp, 0)
        root.addView(card, llp(MATCH, WRAP))
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
        val okLabel = label("✓ On", 14f, C.GREEN, bold = true)
        val btn = button(action, C.BLUE, onClick)
        right.addView(okLabel, flp(WRAP, WRAP, Gravity.CENTER))
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
        sb.progressTintList = ColorStateList.valueOf(C.BLUE)
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
