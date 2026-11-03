package com.moongames.dynamicisland

import android.app.PendingIntent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.media.session.MediaController
import android.os.SystemClock

enum class LiveKind(val priority: Int) {
    CALL(100), TIMER(80), NAV(70), MEDIA(60), PROGRESS(50), PROMPT(10)
}

class NotifAction(val title: CharSequence, val intent: PendingIntent?)

/** Something that stays in the island while it's happening (a call, a timer, music…). */
class LiveItem(val key: String, var kind: LiveKind) {
    var pkg = ""
    var appName = ""
    var icon: Drawable? = null
    var title = ""
    var text = ""
    var hasChrono = false
    var countDown = false
    var chronoBase = 0L
    var progress = -1f
    var accent = Color.WHITE
    var contentIntent: PendingIntent? = null
    var actions: List<NotifAction> = emptyList()
    var incoming = false
    var emoji = ""
    var media: MediaInfo? = null
    var notifKey: String? = null
    var autoCancel = false

    fun signature(): String =
        "$key:$kind:$incoming:" + actions.joinToString("|") { it.title.toString() }

    fun chronoText(): String {
        val now = System.currentTimeMillis()
        return if (countDown) {
            val left = chronoBase - now
            if (left <= 0) "0:00" else fmtDuration(left + 999)
        } else {
            fmtDuration(now - chronoBase)
        }
    }
}

class MediaInfo(
    val pkg: String,
    val title: String,
    val artist: String,
    val art: Bitmap?,
    val playing: Boolean,
    val duration: Long,
    val position: Long,
    val positionTime: Long,
    val speed: Float,
    val controller: MediaController
) {
    fun currentPosition(): Long {
        if (!playing || positionTime <= 0) return position
        val elapsed = SystemClock.elapsedRealtime() - positionTime
        return position + (elapsed * speed).toLong()
    }
}

enum class ToastStyle { BANNER, CARD }

/** A short pop-out (a notification, charging, silent mode…). */
class ToastItem(
    val style: ToastStyle,
    val title: String,
    val text: String = "",
    val icon: Drawable? = null,
    val emoji: String = "",
    val accent: Int = Color.WHITE,
    val value: String = "",
    val battery: Int = -1,
    val charging: Boolean = false,
    val duration: Long = 3500L,
    val appName: String = "",
    val onTap: (() -> Unit)? = null,
    /** Small app icon shown on the corner of a contact photo (iOS-style). */
    val badge: Drawable? = null,
    /** Round (contact photo) vs rounded-square (app icon). */
    val iconRound: Boolean = false,
    /** Coloured capsule behind the icon, like iOS's red Silent bell. 0 = none. */
    val iconBg: Int = 0
) {
    val id: Int = nextId++

    companion object {
        private var nextId = 1
    }
}
