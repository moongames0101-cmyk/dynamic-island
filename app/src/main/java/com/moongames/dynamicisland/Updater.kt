package com.moongames.dynamicisland

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Self-updater. Every push to GitHub builds a new APK and publishes it as a Release;
 * this checks the latest Release, downloads the APK and installs it over the current app.
 */
object Updater {
    const val CHANNEL = "updates"
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    class Release(val versionCode: Int, val name: String, val notes: String, val apkUrl: String, val size: Long)

    fun createChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    private fun fetchLatest(): Release {
        val c = URL("https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases/latest")
            .openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 20000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "DynamicIsland/${BuildConfig.VERSION_NAME}")
        try {
            val code = c.responseCode
            if (code == 404) throw Exception("No versions published yet")
            if (code != 200) throw Exception("GitHub replied $code")
            val json = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val tag = json.optString("tag_name")
            val vc = tag.substringAfterLast('-').toIntOrNull() ?: throw Exception("Unknown release tag: $tag")
            val assets = json.optJSONArray("assets") ?: throw Exception("Release has no files")
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) {
                    return Release(
                        vc,
                        json.optString("name", tag),
                        json.optString("body", ""),
                        a.optString("browser_download_url"),
                        a.optLong("size")
                    )
                }
            }
            throw Exception("Release has no APK yet")
        } finally {
            c.disconnect()
        }
    }

    fun fetchAsync(cb: (Release?, String?) -> Unit) {
        io.execute {
            try {
                val r = fetchLatest()
                main.post { cb(r, null) }
            } catch (e: Exception) {
                main.post { cb(null, e.message ?: "No internet connection") }
            }
        }
    }

    private fun openFollowing(start: String): HttpURLConnection {
        var url = URL(start)
        repeat(6) {
            val c = url.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15000
            c.readTimeout = 30000
            c.setRequestProperty("User-Agent", "DynamicIsland")
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location")
                c.disconnect()
                if (loc == null) throw Exception("Bad redirect")
                url = URL(url, loc)
            } else if (code == 200) {
                return c
            } else {
                c.disconnect()
                throw Exception("Download failed ($code)")
            }
        }
        throw Exception("Too many redirects")
    }

    fun downloadAsync(ctx: Context, rel: Release, progress: (Int) -> Unit, done: (File?, String?) -> Unit) {
        val app = ctx.applicationContext
        io.execute {
            try {
                val out = File(app.cacheDir, "update.apk")
                val c = openFollowing(rel.apkUrl)
                val total = if (c.contentLengthLong > 0) c.contentLengthLong else rel.size
                c.inputStream.use { input ->
                    FileOutputStream(out).use { o ->
                        val buf = ByteArray(64 * 1024)
                        var read = 0L
                        var last = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            o.write(buf, 0, n)
                            read += n
                            if (total > 0) {
                                val p = (read * 100 / total).toInt()
                                if (p != last) {
                                    last = p
                                    main.post { progress(p) }
                                }
                            }
                        }
                    }
                }
                c.disconnect()
                main.post { done(out, null) }
            } catch (e: Exception) {
                main.post { done(null, e.message ?: "Download failed") }
            }
        }
    }

    /** Hands the APK to Android's installer. On Android 12+ it can update silently. */
    fun installAsync(ctx: Context, apk: File, done: ((String?) -> Unit)? = null) {
        val app = ctx.applicationContext
        io.execute {
            try {
                val installer = app.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                params.setAppPackageName(app.packageName)
                if (Build.VERSION.SDK_INT >= 31) {
                    params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
                val id = installer.createSession(params)
                installer.openSession(id).use { s ->
                    FileInputStream(apk).use { input ->
                        s.openWrite("base.apk", 0, apk.length()).use { out ->
                            input.copyTo(out)
                            s.fsync(out)
                        }
                    }
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                        (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    val pi = PendingIntent.getBroadcast(app, id, Intent(app, InstallReceiver::class.java), flags)
                    s.commit(pi.intentSender)
                }
                main.post { done?.invoke(null) }
            } catch (e: Exception) {
                main.post { done?.invoke(e.message ?: "Install failed") }
            }
        }
    }

    /** Runs from the island service every few hours. */
    fun backgroundCheck(svc: IslandService) {
        fetchAsync { rel, _ ->
            if (rel == null || rel.versionCode <= BuildConfig.VERSION_CODE) return@fetchAsync
            val p = Prefs.get(svc)
            val auto = p.getBoolean(P.AUTO_UPDATE, true)
            val canInstall = svc.packageManager.canRequestPackageInstalls()
            if (auto && canInstall && p.getInt(P.AUTO_TRIED, 0) != rel.versionCode) {
                p.edit().putInt(P.AUTO_TRIED, rel.versionCode).apply()
                downloadAsync(svc, rel, {}) { file, _ ->
                    if (file != null) installAsync(svc, file) else svc.showUpdateToast(rel)
                }
            } else {
                if (p.getInt(P.NOTIFIED, 0) != rel.versionCode) {
                    p.edit().putInt(P.NOTIFIED, rel.versionCode).apply()
                    notifyUpdate(svc, rel)
                }
                svc.showUpdateToast(rel)
            }
        }
    }

    fun notifyUpdate(ctx: Context, rel: Release) {
        val i = Intent(ctx, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_UPDATE, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(ctx, 1, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        post(ctx, 42, "Dynamic Island update", "${rel.name} is ready — tap to install", pi)
    }

    fun notifyConfirm(ctx: Context, confirm: Intent) {
        val pi = PendingIntent.getActivity(ctx, 2, confirm, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        post(ctx, 43, "Finish updating Dynamic Island", "Tap to install the new version", pi)
    }

    private fun post(ctx: Context, id: Int, title: String, text: String, pi: PendingIntent) {
        try {
            createChannel(ctx)
            val n = Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_download)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            ctx.getSystemService(NotificationManager::class.java)?.notify(id, n)
        } catch (e: Exception) {
        }
    }
}

/** Receives the installer's answer; if Android wants the user to confirm, shows that screen. */
class InstallReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm: Intent = (
                    if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    ) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(confirm)
                } catch (e: Exception) {
                }
                if (!MainActivity.visible && IslandService.instance == null) {
                    Updater.notifyConfirm(context, confirm)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown error"
                Toast.makeText(context, "Update failed: $msg", Toast.LENGTH_LONG).show()
            }
        }
    }
}
