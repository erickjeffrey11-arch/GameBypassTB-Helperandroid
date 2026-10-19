package com.erick.gamebypass

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Check for updates.
 * Asks GitHub for the newest build of this app, downloads it, and asks Android to install it.
 * Each GitHub build is called build-N, and N is also this app's version number,
 * so "newer" just means a bigger N.
 */
object Updater {
    private const val LATEST =
        "https://api.github.com/repos/erickjeffrey11-arch/GameBypassTB-Helperandroid/releases/latest"

    data class Release(val build: Int, val apkUrl: String)

    /** Runs on a background thread. */
    fun fetchLatest(): Release {
        val conn = URL(LATEST).openConnection() as HttpURLConnection
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        val code = conn.responseCode
        if (code == 404) throw IOException("GitHub couldn't find the app. The repository may still be private.")
        if (code != 200) throw IOException("GitHub answered with error $code.")
        val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        val build = json.getString("tag_name").removePrefix("build-").toIntOrNull()
            ?: throw IOException("Couldn't understand the newest version's name.")
        val assets = json.getJSONArray("assets")
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.getString("name").endsWith(".apk")) {
                return Release(build, a.getString("browser_download_url"))
            }
        }
        throw IOException("The newest version has no APK attached.")
    }

    /** Runs on a background thread. Follows GitHub's redirect to the real file. */
    fun download(url: String, target: File) {
        var address = url
        repeat(5) {
            val conn = URL(address).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            when (conn.responseCode) {
                in 300..399 -> {
                    address = conn.getHeaderField("Location") ?: throw IOException("Bad redirect.")
                }
                200 -> {
                    conn.inputStream.use { input -> target.outputStream().use { input.copyTo(it) } }
                    return
                }
                else -> throw IOException("Download failed with error ${conn.responseCode}.")
            }
        }
        throw IOException("Too many redirects.")
    }

    /** Hands the downloaded APK to Android's installer. Android then asks you to confirm. */
    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("update.apk", 0, apk.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val intent = Intent(context, InstallResultReceiver::class.java)
            val pending = PendingIntent.getBroadcast(
                context, id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            session.commit(pending.intentSender)
        }
    }
}

/** Android reports back here: either "please confirm" or "done/failed". */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                confirm?.let { context.startActivity(it) }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                Toast.makeText(context, "Update installed.", Toast.LENGTH_LONG).show()
            }
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown reason"
                Toast.makeText(context, "Update failed: $msg", Toast.LENGTH_LONG).show()
            }
        }
    }
}
