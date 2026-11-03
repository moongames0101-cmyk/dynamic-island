package com.moongames.dynamicisland

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import java.util.concurrent.Executors

/** Sensible quick apps until you pick your own: phone, messages, camera, browser, settings. */
object QuickDefaults {
    const val MAX = 7

    fun packages(ctx: Context): List<String> {
        val pm = ctx.packageManager
        val out = mutableListOf<String>()
        try {
            (ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager).defaultDialerPackage?.let { out.add(it) }
        } catch (e: Exception) {
        }
        try {
            Telephony.Sms.getDefaultSmsPackage(ctx)?.let { out.add(it) }
        } catch (e: Exception) {
        }
        try {
            pm.resolveActivity(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), 0)?.activityInfo?.packageName?.let { out.add(it) }
        } catch (e: Exception) {
        }
        try {
            pm.resolveActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")), 0)
                ?.activityInfo?.packageName?.takeIf { it != "android" }?.let { out.add(it) }
        } catch (e: Exception) {
        }
        try {
            pm.resolveActivity(Intent(Settings.ACTION_SETTINGS), 0)?.activityInfo?.packageName?.let { out.add(it) }
        } catch (e: Exception) {
        }
        return out.distinct().filter { pm.getLaunchIntentForPackage(it) != null }
    }
}

/** Pick which apps can use the island, and which ones are in your quick apps tray. */
@Suppress("DEPRECATION")
class AppsActivity : Activity() {

    private class App(val pkg: String, val label: String)

    private lateinit var prefs: SharedPreferences
    private var all = listOf<App>()
    private var shown = listOf<App>()
    private val blocked = HashSet<String>()
    private val quick = mutableListOf<String>()
    private val icons = HashMap<String, Drawable?>()
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var adapter: BaseAdapter
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs.get(this)
        blocked.addAll(prefs.getStringSet(P.BLOCKED_APPS, emptySet()) ?: emptySet())
        val saved = (prefs.getString(P.QUICK_APPS, "") ?: "").split(',').filter { it.isNotBlank() }
        quick.addAll(saved.ifEmpty { QuickDefaults.packages(this) })

        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        val root = vbox()
        root.setBackgroundColor(Color.BLACK)
        root.setPadding(18.dp, 48.dp, 18.dp, 0)

        root.addView(label("Choose apps", 30f, Color.WHITE, bold = true))
        val sub = label(
            "Switch an app off to keep its notifications, calls and music out of the island. " +
                "Tap ★ to put it in your quick apps — touch and hold the island when nothing's happening to open them.",
            13.5f, C.GRAY, lines = 5
        )
        sub.setPadding(0, 8.dp, 0, 12.dp)
        root.addView(sub)

        val search = EditText(this)
        search.hint = "Search apps"
        search.setHintTextColor(C.GRAY)
        search.setTextColor(Color.WHITE)
        search.textSize = 15f
        search.setSingleLine(true)
        search.inputType = InputType.TYPE_CLASS_TEXT
        search.background = roundRect(C.CARD, 14f.dpf)
        search.setPadding(14.dp, 0, 14.dp, 0)
        root.addView(search, llp(MATCH, 44.dp))

        status = label("Loading apps…", 13f, C.GRAY)
        status.setPadding(4.dp, 12.dp, 0, 6.dp)
        root.addView(status)

        val list = ListView(this)
        list.divider = null
        list.isVerticalScrollBarEnabled = false
        list.setBackgroundColor(Color.BLACK)
        list.clipToPadding = false
        adapter = object : BaseAdapter() {
            override fun getCount(): Int = shown.size
            override fun getItem(position: Int): Any = shown[position]
            override fun getItemId(position: Int): Long = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View =
                bindRow(convertView as? LinearLayout ?: makeRow(), shown[position])
        }
        list.adapter = adapter
        root.addView(list, llp(MATCH, 0, 1f))

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = filter(s?.toString() ?: "")
        })

        root.setOnApplyWindowInsetsListener { _, insets ->
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
            root.setPadding(18.dp, top + 16.dp, 18.dp, 0)
            list.setPadding(0, 0, 0, bottom + 16.dp)
            insets
        }
        setContentView(root)
        load()
    }

    private fun load() {
        io.execute {
            val pm = packageManager
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val apps = pm.queryIntentActivities(launcher, 0)
                .map { it.activityInfo.packageName }
                .distinct()
                .filter { it != packageName }
                .map { pkg ->
                    val label = try {
                        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                    } catch (e: Exception) {
                        pkg
                    }
                    App(pkg, label)
                }
                .sortedBy { it.label.lowercase(Locale.getDefault()) }
            main.post {
                all = apps
                filter("")
            }
        }
    }

    private fun filter(q: String) {
        val query = q.trim().lowercase(Locale.getDefault())
        shown = if (query.isEmpty()) all else all.filter { it.label.lowercase(Locale.getDefault()).contains(query) }
        updateStatus()
        adapter.notifyDataSetChanged()
    }

    private fun updateStatus() {
        val off = blocked.size
        status.text = "${all.size} apps · ${if (off == 0) "all on" else "$off switched off"} · ${quick.size} quick apps"
    }

    private fun makeRow(): LinearLayout {
        val row = hbox()
        row.setPadding(4.dp, 8.dp, 0, 8.dp)
        val icon = ImageView(this)
        icon.tag = "icon"
        row.addView(icon, llp(40.dp, 40.dp))
        val name = label("", 15.5f, Color.WHITE)
        name.tag = "name"
        name.setPadding(12.dp, 0, 8.dp, 0)
        row.addView(name, llp(0, WRAP, 1f))
        val star = label("☆", 22f, C.GRAY)
        star.tag = "star"
        star.gravity = Gravity.CENTER
        row.addView(star, llp(44.dp, 44.dp))
        val sw = Switch(this)
        sw.tag = "switch"
        row.addView(sw, llp(WRAP, WRAP).apply { marginStart = 4.dp })
        return row
    }

    private fun bindRow(row: LinearLayout, app: App): View {
        val icon = row.findViewWithTag<ImageView>("icon")
        val name = row.findViewWithTag<TextView>("name")
        val star = row.findViewWithTag<TextView>("star")
        val sw = row.findViewWithTag<Switch>("switch")
        icon.setImageDrawable(icons.getOrPut(app.pkg) {
            try {
                packageManager.getApplicationIcon(app.pkg)
            } catch (e: Exception) {
                null
            }
        })
        name.text = app.label

        val isQuick = app.pkg in quick
        star.text = if (isQuick) "★" else "☆"
        star.setTextColor(if (isQuick) C.YELLOW else C.GRAY)
        star.setOnClickListener {
            if (app.pkg in quick) {
                quick.remove(app.pkg)
            } else {
                if (quick.size >= QuickDefaults.MAX) {
                    Toast.makeText(this, "You can have up to ${QuickDefaults.MAX} quick apps", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                quick.add(app.pkg)
            }
            save()
            bindRow(row, app)
        }

        sw.setOnCheckedChangeListener(null)
        sw.isChecked = app.pkg !in blocked
        sw.setOnCheckedChangeListener { _, on ->
            if (on) blocked.remove(app.pkg) else blocked.add(app.pkg)
            save()
        }
        name.alpha = if (app.pkg in blocked) 0.5f else 1f
        icon.alpha = name.alpha
        return row
    }

    private fun save() {
        prefs.edit()
            .putStringSet(P.BLOCKED_APPS, HashSet(blocked))
            .putString(P.QUICK_APPS, quick.joinToString(","))
            .apply()
        updateStatus()
        adapter.notifyDataSetChanged()
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }
}
