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
    const val HIDE_ICONS = "hide_icons"
    const val CLOCK_RIGHT = "clock_right"
    const val NATIVE_ICONS = "native_icons"
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

    val FEATURES = listOf(
        Feature(F_NOTIFICATIONS, "Notifications", "Messages and alerts drop out of the island. Tip: turn off Android's own pop-up banners so you don't get two."),
        Feature(F_MEDIA, "Music & video", "Album art and a moving waveform. Tap for play/pause, skip and a seek bar."),
        Feature(F_CALLS, "Calls", "Incoming calls with Answer / Decline, and a call timer while you talk."),
        Feature(F_TIMERS, "Timers & stopwatch", "Live countdown from your Clock app (and screen recorders)."),
        Feature(F_NAVIGATION, "Maps directions", "Next turn from Google Maps and other navigation apps."),
        Feature(F_PROGRESS, "Downloads & uploads", "Progress ring for anything showing a progress bar."),
        Feature(F_CHARGING, "Charging", "Battery animation when you plug in, and Fully Charged."),
        Feature(F_LOWBATTERY, "Low battery", "Red warning when you drop to 20%."),
        Feature(F_RINGER, "Silent / Ring", "Shows when you switch sound mode."),
        Feature(F_FLASHLIGHT, "Flashlight", "Shows when the torch turns on or off."),
        Feature(F_BLUETOOTH, "Headphones", "Bluetooth devices connecting, with battery %."),
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
    private const val SLOTS = "rotate,headset,alarm_clock,battery,bluetooth,cast,data_saver,dnd,zen," +
        "hotspot,location,managed_profile,mobile,nfc,speakerphone,volume,vpn,wifi,ethernet,airplane," +
        "call_strength,ims,wifi_calling,sensors_off,mic,camera,screen_record,connected_display,tty," +
        "cast,power_save,network_speed"

    fun canWrite(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun apply(ctx: Context, on: Boolean) {
        if (!canWrite(ctx)) return
        val p = Prefs.get(ctx)
        try {
            val cr = ctx.contentResolver
            if (on) {
                val current = Settings.Secure.getString(cr, KEY)
                if (current != SLOTS) p.edit().putString(P.SAVED_BLACKLIST, current ?: "").apply()
                Settings.Secure.putString(cr, KEY, SLOTS)
            } else {
                val saved = p.getString(P.SAVED_BLACKLIST, "") ?: ""
                Settings.Secure.putString(cr, KEY, saved.ifEmpty { null })
            }
        } catch (e: Exception) {
        }
    }
}
