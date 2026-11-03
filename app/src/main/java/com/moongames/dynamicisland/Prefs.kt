package com.moongames.dynamicisland

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject

class Feature(val key: String, val label: String, val desc: String)

object P {
    const val ENABLED = "enabled"
    const val NATIVE_ICONS = "native_icons"
    const val NATIVE_APPLIED = "native_applied"
    const val SAVED_BLACKLIST = "saved_blacklist"
    const val WIDTH = "island_w"
    const val HEIGHT = "island_h"
    const val OFF_X = "off_x"
    const val OFF_Y = "off_y"
    const val IDLE_VISIBLE = "idle_visible"
    const val HIDE_FULLSCREEN = "hide_fullscreen"
    const val TOAST_SECS = "toast_secs"
    const val AUTO_UPDATE = "auto_update"
    const val AUTO_TRIED = "auto_tried"
    const val NOTIFIED = "notified_version"
    const val SEEN_VERSION = "seen_version"
    const val PROMPTS = "prompts"
    const val PINNED = "pinned"
    const val BLOCKED_APPS = "blocked_apps"
    const val QUICK_APPS = "quick_apps"
    const val HIDE_LOCKED = "hide_locked"
    const val SWIPE_CLEARS = "swipe_clears"
    const val ANIM_SPEED = "anim_speed"
    const val HAPTICS = "haptics"
    const val SETUP_OPEN = "setup_open"

    const val F_NOTIFICATIONS = "f_notifications"
    const val F_MEDIA = "f_media"
    const val F_CALLS = "f_calls"
    const val F_TIMERS = "f_timers"
    const val F_NAVIGATION = "f_navigation"
    const val F_PROGRESS = "f_progress"
    const val F_CHARGING = "f_charging"
    const val F_LOWBATTERY = "f_lowbattery"
    const val F_RINGER = "f_ringer"
    const val F_FLASHLIGHT = "f_flashlight"
    const val F_BLUETOOTH = "f_bluetooth"
    const val F_PROMPTS = "f_prompts"
    const val F_UNLOCK = "f_unlock"
    const val F_ALARMS = "f_alarms"
    const val F_DND = "f_dnd"
    const val F_VOLUME = "f_volume"

    val FEATURES = listOf(
        Feature(F_NOTIFICATIONS, "Notifications", "Messages and alerts drop out of the island. Tip: turn off Android's own pop-up banners so you don't get two."),
        Feature(F_MEDIA, "Music & video", "Album art and a moving waveform. Tap for play/pause, skip and a seek bar."),
        Feature(F_CALLS, "Calls", "Incoming calls with Answer / Decline, and a call timer while you talk. Allow \"Calls\" in Setup so it works with every phone app."),
        Feature(F_TIMERS, "Timers & stopwatch", "Live countdown from your Clock app (and screen recorders). Hold the island → Timer to start one of your own."),
        Feature(F_ALARMS, "Alarms", "A ringing alarm shows in the island with Snooze and Stop."),
        Feature(F_NAVIGATION, "Maps directions", "Next turn from Google Maps and other navigation apps."),
        Feature(F_PROGRESS, "Downloads & uploads", "Progress ring for anything showing a progress bar."),
        Feature(F_CHARGING, "Charging", "Battery animation when you plug in, and Fully Charged."),
        Feature(F_LOWBATTERY, "Low battery", "Red warning when you drop to 20%."),
        Feature(F_RINGER, "Silent / Ring", "Shows when you switch sound mode."),
        Feature(F_FLASHLIGHT, "Flashlight", "Shows when the torch turns on or off."),
        Feature(F_DND, "Focus / Do Not Disturb", "A moon pops up when Do Not Disturb turns on or off."),
        Feature(F_VOLUME, "Volume", "Volume changes show as a little level bar in the island."),
        Feature(F_UNLOCK, "Unlock", "A padlock pops open when you unlock with your face or fingerprint."),
        Feature(F_BLUETOOTH, "Headphones", "Bluetooth headphones connecting (with battery %) and wired headphones plugged in."),
        Feature(F_PROMPTS, "Custom prompts", "Your own messages — on demand, pinned, or every day at a set time.")
    )
}

object Prefs {
    fun get(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("island", Context.MODE_PRIVATE)

    fun seed(p: SharedPreferences) {
        if (!p.contains(P.PROMPTS)) {
            Prompts.save(p, mutableListOf(
                Prompt(1L, "Have a great day!", "✨", "", ""),
                Prompt(2L, "Good morning ☀️ Drink some water", "💧", "08:00", "")
            ))
        }
    }
}

/** Default island size: about as tall as the status bar, iPhone proportions, hugging the camera. */
object AutoSize {
    @android.annotation.SuppressLint("DiscouragedApi", "InternalInsetResource")
    fun heightDp(ctx: Context): Int {
        val res = ctx.resources
        val id = res.getIdentifier("status_bar_height", "dimen", "android")
        val px = if (id > 0) res.getDimensionPixelSize(id) else 0
        val dp = if (px > 0) Math.round(px / res.displayMetrics.density) else 30
        // iPhone's island is ~37dp tall; most Android status bars are a little shorter, so
        // sit just inside the bar but never go tiny.
        return (dp - 1).coerceIn(30, 37)
    }

    fun widthDp(ctx: Context): Int = Math.round(heightDp(ctx) * 3.4f)
}

class Prompt(val id: Long, var text: String, var emoji: String, var time: String, var lastShown: String)

object Prompts {
    fun load(p: SharedPreferences): MutableList<Prompt> {
        val out = mutableListOf<Prompt>()
        try {
            val arr = JSONArray(p.getString(P.PROMPTS, "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Prompt(o.optLong("id"), o.optString("text"), o.optString("emoji"), o.optString("time"), o.optString("last")))
            }
        } catch (e: Exception) {
        }
        return out
    }

    fun save(p: SharedPreferences, list: List<Prompt>) {
        val arr = JSONArray()
        for (it in list) {
            val o = JSONObject()
            o.put("id", it.id)
            o.put("text", it.text)
            o.put("emoji", it.emoji)
            o.put("time", it.time)
            o.put("last", it.lastShown)
            arr.put(o)
        }
        p.edit().putString(P.PROMPTS, arr.toString()).apply()
    }
}

/** "Native mode": hides the real status bar icons (needs a one-time ADB grant). */
object StatusIcons {
    private const val KEY = "icon_blacklist"

    // Known slot names (AOSP + Samsung's call / Wi-Fi Calling / VoLTE icons). Unknown names are harmless.
    private val EXTRA = listOf(
        "rotate", "headset", "alarm_clock", "battery", "bluetooth", "cast", "data_saver", "dnd", "zen",
        "hotspot", "location", "managed_profile", "mobile", "nfc", "speakerphone", "volume", "vpn", "wifi",
        "ethernet", "airplane", "call_strength", "ims", "wifi_calling", "sensors_off", "mic", "camera",
        "screen_record", "connected_display", "tty", "power_save", "network_speed", "mute", "phone",
        "volte", "vowifi", "volte1", "volte2", "vowifi1", "vowifi2", "ims_volte", "ims_vowifi", "hd_voice",
        "wifi_call", "voice_call", "call_record", "call_forward", "call_forwarding", "cdma_eri", "tty_mode",
        "smart_bonding", "wifi_p2p", "dual_sim", "sim_card", "data_connection", "nr", "ims_registration",
        "knox_container", "secure_folder", "dex", "glove", "power_saving", "sync_active", "sync_failing",
        "alarm", "missed_call", "battery_saver", "privacy_indicator", "dnd_mode"
    )

    /** Every status bar icon slot the phone knows about, except the clock. */
    @android.annotation.SuppressLint("DiscouragedApi")
    private fun slots(): String {
        val all = LinkedHashSet<String>()
        try {
            val res = android.content.res.Resources.getSystem()
            val id = res.getIdentifier("config_statusBarIcons", "array", "android")
            if (id != 0) res.getStringArray(id).forEach { if (it.isNotBlank()) all.add(it.trim()) }
        } catch (e: Exception) {
        }
        all.addAll(EXTRA)
        all.remove("clock")
        return all.joinToString(",")
    }

    fun canWrite(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun apply(ctx: Context, on: Boolean) {
        if (!canWrite(ctx)) return
        val p = Prefs.get(ctx)
        try {
            val cr = ctx.contentResolver
            if (on) {
                val slots = slots()
                val current = Settings.Secure.getString(cr, KEY)
                if (!p.getBoolean(P.NATIVE_APPLIED, false)) {
                    // Remember the phone's original list (ignore a list written by an older version of this app).
                    val original = if (current != null && current.contains("connected_display")) "" else current ?: ""
                    p.edit().putString(P.SAVED_BLACKLIST, original).putBoolean(P.NATIVE_APPLIED, true).apply()
                }
                Settings.Secure.putString(cr, KEY, slots)
            } else {
                val saved = p.getString(P.SAVED_BLACKLIST, "") ?: ""
                Settings.Secure.putString(cr, KEY, saved.ifEmpty { null })
                p.edit().putBoolean(P.NATIVE_APPLIED, false).apply()
            }
        } catch (e: Exception) {
        }
    }
}
