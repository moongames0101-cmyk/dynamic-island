package com.moongames.dynamicisland

import android.app.Notification
import android.app.PendingIntent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.media.session.MediaController
import android.os.SystemClock

enum class LiveKind(val priority: Int) {
    CALL(100), TIMER(80), NAV(70), MEDIA(60), PROGRESS(50), PROMPT(10)
}

/**
 * A button: either an app's notification action ([intent]), something we do ourselves ([run]),
 * or a reply box ([reply] holds the app's RemoteInput action).
 */
class NotifAction(
    val title: CharSequence,
    val intent: PendingIntent?,
    val run: (() -> Unit)? = null,
    val reply: Notification.Action? = null
)

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

enum class ToastStyle { BANNER, CARD, UNLOCK }

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
    val iconBg: Int = 0,
    /** The app's buttons (Reply, Mark as read…), shown when you touch and hold. */
    val actions: List<NotifAction> = emptyList(),
    /** The notification this came from, so swiping it away can clear it. */
    val notifKey: String? = null
) {
    val expandable: Boolean get() = style == ToastStyle.CARD && (actions.isNotEmpty() || text.length > 60)

    val id: Int = nextId++

    companion object {
        private var nextId = 1
    }
}

/** Small, content-free log of what the island saw, shown in the app to help fix phone quirks. */
object Diag {
    private val lines = ArrayDeque<String>()

    @Synchronized
    fun add(line: String) {
        val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        lines.addFirst("$t  $line")
        while (lines.size > 25) lines.removeLast()
    }

    @Synchronized
    fun dump(): String = if (lines.isEmpty()) "Nothing yet — get a notification or a call, then come back." else lines.joinToString("\n")
}
