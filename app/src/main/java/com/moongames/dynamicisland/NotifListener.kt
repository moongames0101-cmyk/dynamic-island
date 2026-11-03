package com.moongames.dynamicisland

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Drawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification

/** Watches notifications (and media sessions) and feeds them to the island. */
class NotifListener : NotificationListenerService() {

    companion object {
        @Volatile
        var instance: NotifListener? = null

        private val DIALERS = setOf(
            "com.samsung.android.incallui", "com.samsung.android.dialer", "com.android.incallui",
            "com.google.android.dialer", "com.android.dialer", "com.android.server.telecom"
        )
    }

    private var media: MediaTracker? = null
    private val lastAlert = HashMap<String, Long>()
    private val lastContent = HashMap<String, String>()
    private var dndOn: Boolean? = null

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Diag.add("Notifications connected")
        val m = MediaTracker(this)
        media = m
        m.start()
        dndOn = try {
            currentInterruptionFilter != NotificationListenerService.INTERRUPTION_FILTER_ALL
        } catch (e: Exception) {
            null
        }
        resync()
    }

    override fun onInterruptionFilterChanged(filter: Int) {
        super.onInterruptionFilterChanged(filter)
        val on = filter != NotificationListenerService.INTERRUPTION_FILTER_ALL &&
            filter != NotificationListenerService.INTERRUPTION_FILTER_UNKNOWN
        val prev = dndOn
        dndOn = on
        if (prev != null && prev != on) IslandService.instance?.onDnd(on)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        if (instance === this) instance = null
        media?.stop()
        media = null
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        media?.stop()
        media = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?, rankingMap: RankingMap?) {
        if (sbn != null) safeHandle(sbn, false)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val s = sbn ?: return
        IslandService.instance?.removeLive(s.key)
    }

    /** One strange notification must never crash the listener (that would silence all of them). */
    private fun safeHandle(sbn: StatusBarNotification, initial: Boolean) {
        try {
            handle(sbn, initial)
        } catch (e: Exception) {
            android.util.Log.e("Island", "Problem reading a notification", e)
            Diag.add("Problem reading a notification from ${sbn.packageName}: ${e.javaClass.simpleName}")
        }
    }

    /** Re-sends everything currently showing (used when the island service starts). */
    fun resync() {
        try {
            activeNotifications?.forEach { safeHandle(it, true) }
        } catch (e: Exception) {
        }
        media?.publish()
    }

    fun dismiss(key: String) {
        try {
            cancelNotification(key)
        } catch (e: Exception) {
        }
    }

    /** Records what kind of notification arrived (never its text) for the diagnostics list. */
    private fun diag(pkg: String, n: Notification, cat: String, template: String, result: String) {
        val app = IslandService.instance?.appLabel(pkg) ?: pkg
        val flags = mutableListOf<String>()
        if ((n.flags and Notification.FLAG_ONGOING_EVENT) != 0) flags += "ongoing"
        if ((n.flags and Notification.FLAG_FOREGROUND_SERVICE) != 0) flags += "fg"
        if (n.extras?.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER) == true) flags += "timer"
        if (n.fullScreenIntent != null) flags += "fullscreen"
        val style = template.substringAfterLast('$').substringAfterLast('.')
        Diag.add(
            "$app ($pkg)" + (if (cat.isNotEmpty()) " cat=$cat" else "") +
                (if (style.isNotEmpty()) " style=$style" else "") +
                (if (flags.isNotEmpty()) " " + flags.joinToString(",") else "") + " $result"
        )
    }

    private fun str(ex: Bundle, key: String): String = ex.getCharSequence(key)?.toString()?.trim() ?: ""

    @Suppress("DEPRECATION")
    private fun handle(sbn: StatusBarNotification, initial: Boolean) {
        val svc = IslandService.instance ?: return
        val pkg = sbn.packageName ?: return
        if (pkg == packageName) return
        val n = sbn.notification ?: return
        val ex = n.extras ?: Bundle()
        val key = sbn.key
        val cat = n.category ?: ""
        val template = ex.getString(Notification.EXTRA_TEMPLATE) ?: ""

        // Music/video is handled through media sessions instead.
        if (template.contains("MediaStyle") || cat == "transport") return

        // Apps you've switched off in "Choose apps".
        if (pkg in svc.blockedApps()) {
            if (svc.liveKind(key) != null) svc.removeLive(key)
            if (!initial) diag(pkg, n, cat, template, "skipped (app switched off)")
            return
        }

        val ongoing = (n.flags and Notification.FLAG_ONGOING_EVENT) != 0 ||
            (n.flags and Notification.FLAG_FOREGROUND_SERVICE) != 0
        val showChrono = ex.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)
        val countDown = ex.getBoolean("android.chronometerCountDown")
        val pMax = ex.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        val prog = ex.getInt(Notification.EXTRA_PROGRESS, 0)
        val indeterminate = ex.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE)
        val callType = ex.getInt("android.callType", 0)
        // Samsung's (and Google's) phone apps don't always tag their in-call notification as a call.
        val dialer = pkg in DIALERS
        val isCall = cat == "call" || template.contains("CallStyle") || (dialer && ongoing)
        val incoming = isCall && (callType == 1 || (!showChrono && n.fullScreenIntent != null))

        // A ringing alarm: Clock posts it with a full-screen intent. ("Upcoming alarm" reminders don't have one.)
        val isAlarm = cat == "alarm" && n.fullScreenIntent != null && !isCall

        var kind: LiveKind? = when {
            isCall && (ongoing || callType != 0 || n.fullScreenIntent != null) -> LiveKind.CALL
            isAlarm -> LiveKind.ALARM
            cat == "navigation" && ongoing -> LiveKind.NAV
            showChrono && ongoing -> LiveKind.TIMER
            ongoing && svc.liveKind(key) == LiveKind.TIMER -> LiveKind.TIMER
            ongoing && pMax > 0 && !indeterminate -> LiveKind.PROGRESS
            else -> null
        }
        val featureKey = when (kind) {
            LiveKind.CALL -> P.F_CALLS
            LiveKind.ALARM -> P.F_ALARMS
            LiveKind.NAV -> P.F_NAVIGATION
            LiveKind.TIMER -> P.F_TIMERS
            LiveKind.PROGRESS -> P.F_PROGRESS
            else -> null
        }
        if (featureKey != null && !svc.feature(featureKey)) kind = null

        // Title / message (for chats, use the newest message).
        var title = str(ex, Notification.EXTRA_TITLE).ifEmpty { str(ex, Notification.EXTRA_TITLE_BIG) }
        var body = str(ex, Notification.EXTRA_TEXT).ifEmpty { str(ex, Notification.EXTRA_BIG_TEXT) }
        try {
            val msgs = ex.getParcelableArray(Notification.EXTRA_MESSAGES)
            val last = msgs?.lastOrNull() as? Bundle
            if (last != null) {
                last.getCharSequence("text")?.toString()?.let { if (it.isNotBlank()) body = it }
                val sender = last.getCharSequence("sender")?.toString()
                val convo = str(ex, Notification.EXTRA_CONVERSATION_TITLE)
                if (convo.isNotEmpty() && !sender.isNullOrBlank()) {
                    title = convo
                    body = "$sender: $body"
                } else if (!sender.isNullOrBlank()) {
                    title = sender
                }
            }
        } catch (e: Exception) {
        }

        val appName = svc.appLabel(pkg)
        val appIcon = svc.appIcon(pkg)
        val largeIcon: Drawable? = try {
            n.getLargeIcon()?.loadDrawable(this)
        } catch (e: Exception) {
            null
        }
        val autoCancel = (n.flags and Notification.FLAG_AUTO_CANCEL) != 0

        if (kind != null) {
            val actions = n.actions?.mapNotNull { a ->
                val t = a.title
                if (t == null || t.isEmpty()) null else NotifAction(t, a.actionIntent)
            }?.take(3) ?: emptyList()
            val k: LiveKind = kind
            val wantsYou = incoming || k == LiveKind.ALARM
            if (!initial && svc.liveKind(key) == null) diag(pkg, n, cat, template, "→ ${k.name.lowercase()}" + if (incoming) " (incoming)" else "")
            svc.upsertLive(key, k, autoExpand = wantsYou && !initial) { item ->
                item.pkg = pkg
                item.appName = appName
                item.title = title
                item.text = body
                item.icon = largeIcon ?: appIcon
                item.hasChrono = showChrono
                item.countDown = countDown
                item.chronoBase = n.`when`
                item.progress = if (pMax > 0) prog.toFloat() / pMax else -1f
                item.accent = if (n.color != 0) n.color else Panels.kindColor(k)
                item.contentIntent = n.contentIntent
                item.actions = actions
                item.incoming = incoming || k == LiveKind.ALARM
                item.notifKey = key
                item.autoCancel = autoCancel
            }
            return
        }

        // Not a live activity (any more) – drop it if it was one.
        if (svc.liveKind(key) != null) svc.removeLive(key)

        if (initial) return
        if (ongoing) {
            diag(pkg, n, cat, template, "skipped (ongoing)")
            return
        }
        if (!svc.feature(P.F_NOTIFICATIONS)) return
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        if (title.isEmpty() && body.isEmpty()) {
            body = n.tickerText?.toString()?.trim() ?: ""
            if (body.isEmpty()) return
        }

        // Show every app, including "silent" ones. Only skip notifications that are
        // completely hidden (importance "min" / blocked).
        val ranking = Ranking()
        val ranked = try {
            currentRanking?.getRanking(key, ranking) == true
        } catch (e: Exception) {
            false
        }
        if (ranked && ranking.importance < NotificationManager.IMPORTANCE_LOW) {
            diag(pkg, n, cat, template, "skipped (hidden notification)")
            return
        }

        // A chat that adds a new message keeps the same key: show it again whenever the text
        // changes, but not for silent re-posts of the same content.
        val signature = "$title\u0000$body"
        val now = System.currentTimeMillis()
        val prev = lastAlert[key]
        if (prev != null && now - prev < 1500L) return
        if (lastContent[key] == signature && prev != null && now - prev < 10 * 60 * 1000L) return
        lastAlert[key] = now
        lastContent[key] = signature
        if (lastAlert.size > 300) {
            lastAlert.clear()
            lastContent.clear()
        }

        diag(pkg, n, cat, template, "→ pop-up")
        val contentIntent = n.contentIntent

        // While the phone is locked, show only the app (like iPhone's "Show previews: when unlocked").
        val locked = try {
            (getSystemService(KEYGUARD_SERVICE) as android.app.KeyguardManager).isKeyguardLocked
        } catch (e: Exception) {
            false
        }
        val hidden = locked && svc.prefs.getBoolean(P.HIDE_LOCKED, true)

        // The app's buttons: Reply (with a text box), Mark as read, etc.
        val actions = if (hidden) emptyList() else n.actions?.mapNotNull { a ->
            val t = a.title
            if (t == null || t.isEmpty()) null
            else if (!a.remoteInputs.isNullOrEmpty()) NotifAction(t, a.actionIntent, reply = a)
            else NotifAction(t, a.actionIntent)
        }?.sortedByDescending { it.reply != null }?.take(3) ?: emptyList()

        svc.showToast(
            ToastItem(
                ToastStyle.CARD,
                title = if (hidden) appName else title.ifEmpty { appName },
                text = if (hidden) "" else body,
                icon = if (hidden) appIcon else largeIcon ?: appIcon,
                badge = if (!hidden && largeIcon != null) appIcon else null,
                iconRound = !hidden && largeIcon != null,
                appName = if (hidden) "" else appName,
                duration = svc.toastMs(),
                actions = actions,
                notifKey = key,
                onTap = {
                    svc.sendPending(contentIntent)
                    if (autoCancel) dismiss(key)
                }
            )
        )
    }
}

/** Follows whatever is playing (Spotify, YouTube, etc.) through Android's media sessions. */
class MediaTracker(private val ctx: NotificationListenerService) {
    private val handler = Handler(Looper.getMainLooper())
    private val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val comp = ComponentName(ctx, NotifListener::class.java)
    private val tracked = mutableListOf<Pair<MediaController, MediaController.Callback>>()
    private var current: MediaController? = null
    private val listener = MediaSessionManager.OnActiveSessionsChangedListener { list -> track(list ?: emptyList()) }

    fun start() {
        try {
            msm.addOnActiveSessionsChangedListener(listener, comp, handler)
            track(msm.getActiveSessions(comp))
        } catch (e: Exception) {
        }
    }

    fun stop() {
        try {
            msm.removeOnActiveSessionsChangedListener(listener)
        } catch (e: Exception) {
        }
        untrack()
    }

    private fun untrack() {
        for ((c, cb) in tracked) {
            try {
                c.unregisterCallback(cb)
            } catch (e: Exception) {
            }
        }
        tracked.clear()
    }

    private fun track(list: List<MediaController>) {
        untrack()
        for (c in list) {
            val cb = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
                override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
                override fun onSessionDestroyed() = publish()
            }
            try {
                c.registerCallback(cb, handler)
                tracked.add(Pair(c, cb))
            } catch (e: Exception) {
            }
        }
        publish()
    }

    fun publish() {
        val svc = IslandService.instance ?: return
        val controllers = tracked.map { it.first }
        val playing = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        val cur = current
        val chosen = playing ?: cur?.let { c -> controllers.firstOrNull { it.sessionToken == c.sessionToken } }
        current = chosen
        if (chosen == null) {
            svc.setMedia(null)
            return
        }
        val md = chosen.metadata
        val st = chosen.playbackState
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: md?.description?.title?.toString() ?: ""
        if (title.isBlank()) {
            svc.setMedia(null)
            return
        }
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: md?.description?.subtitle?.toString() ?: ""
        val art = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val info = MediaInfo(
            pkg = chosen.packageName ?: "",
            title = title,
            artist = artist,
            art = art,
            playing = st?.state == PlaybackState.STATE_PLAYING,
            duration = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
            position = st?.position ?: 0L,
            positionTime = st?.lastPositionUpdateTime ?: SystemClock.elapsedRealtime(),
            speed = st?.playbackSpeed ?: 1f,
            controller = chosen
        )
        svc.setMedia(info)
    }
}
