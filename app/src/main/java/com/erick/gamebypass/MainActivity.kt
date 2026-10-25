package com.erick.gamebypass

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.view.View
import java.io.File
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** The simple screen you see when you open the app from the app list. */
class MainActivity : Activity() {
    private lateinit var updateStatus: TextView
    private lateinit var updateButton: Button
    private var latest: Updater.Release? = null
    private lateinit var appsBox: LinearLayout
    private lateinit var engineButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }

        layout.addView(TextView(this).apply {
            text = "Game Bypass TB Helper"
            textSize = 24f
            isFocusable = true
            isAccessibilityHeading = true
        })

        layout.addView(TextView(this).apply {
            textSize = 17f
            setPadding(0, pad, 0, pad)
            text = """
                How to turn it on:
                1. Double tap the Open Accessibility Settings button below.
                2. Find Game Bypass Mode in the list (it may be under Downloaded apps) and turn it on.
                3. If Android says it's a restricted setting: go to Settings, Apps, Game Bypass TB Helper, then the More options menu at the top right, and choose Allow restricted settings. Then try step 2 again.

                How it works:
                Keep TalkBack on. Game Bypass stays out of apps TalkBack can already read. When you open a game TalkBack can't read, you'll hear Game Bypass Mode on, and how many items it found. Swipe through them, or slide your finger around the screen. Double tap an item to press it in the game. When new words pop up on the screen you're on, like You win, they're said right away. You can pick the speech engine for these announcements in the Announcement voice section below.

                Along the very bottom edge of the screen there's a thin strip of buttons: Rescan, Direct touch, and Pause. Swipe to reach them.
                Rescan reads the screen again.
                Direct touch sends your taps straight to the game. Turn it off with the same button.
                While direct touch is on, it keeps watching the screen and says any new words the game shows, like Swipe right to dodge, so you can just do it.
                Pause turns Game Bypass Mode off for this game until you leave it.
                To keep Game Bypass off in an app for good, use Add an app in the Apps section below.

                If it finds no text three times in a row, it switches to direct touch by itself, and switches back when a menu with text shows up.

                To see more of a list or a row of items, like more slot machines, swipe to the end of the buttons and double tap Scroll forward or Scroll back. It slides the row of the last button you were on, or scrolls up and down if there's no row or the row doesn't move. Then you hear the new names. By touch, Scroll back is in the very top left corner and Scroll forward in the very top right.

                Sliding your finger anywhere on the screen always says something. Parts with no buttons say Nothing visible if they're blank, or Picture if something is drawn there, plus what it might be, like Picture: coin, chest, and where it is. Those guesses can be wrong on cartoon art.

                When you go home, it turns off and TalkBack works normally.
            """.trimIndent()
        })

        layout.addView(Button(this).apply {
            text = "Open Accessibility Settings"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        })

        // ---- Apps where Game Bypass stays off
        layout.addView(TextView(this).apply {
            text = "Apps"
            textSize = 20f
            setPadding(0, pad * 2, 0, 0)
            isAccessibilityHeading = true
        })
        layout.addView(Button(this).apply {
            text = "Add an app"
            isAllCaps = false
            contentDescription = "Add an app. Choose an app where Game Bypass should always stay off."
            setOnClickListener { pickAppToExclude() }
        })
        appsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        layout.addView(appsBox)

        // ---- Announcement voice
        layout.addView(TextView(this).apply {
            text = "Announcement voice"
            textSize = 20f
            setPadding(0, pad * 2, 0, 0)
            isAccessibilityHeading = true
        })
        engineButton = Button(this).apply {
            isAllCaps = false
            setOnClickListener { pickEngine() }
        }
        layout.addView(engineButton)
        showEngine()

        // ---- Check for updates
        layout.addView(TextView(this).apply {
            text = "Updates"
            textSize = 20f
            setPadding(0, pad * 2, 0, 0)
            isAccessibilityHeading = true
        })
        updateStatus = TextView(this).apply {
            textSize = 17f
            setPadding(0, pad / 2, 0, pad / 2)
            // TalkBack reads this out loud whenever it changes.
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            text = "You have build ${currentBuild()}."
        }
        layout.addView(updateStatus)
        updateButton = Button(this).apply {
            text = "Check for updates"
            setOnClickListener {
                val l = latest
                if (l != null && l.build > currentBuild()) downloadAndInstall(l) else checkForUpdates()
            }
        }
        layout.addView(updateButton)

        setContentView(ScrollView(this).apply { addView(layout) })
        checkForUpdates()
    }

    override fun onResume() {
        super.onResume()
        showApps()
    }

    /**
     * Apps you told Game Bypass to never use, and apps it remembered as accessible.
     * Each has a button to let Game Bypass work there again.
     */
    private fun showApps() {
        val prefs = getSharedPreferences("apps", MODE_PRIVATE)
        appsBox.removeAllViews()
        fun label(pkg: String) = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
        val never = prefs.getStringSet("never", emptySet()) ?: emptySet()
        val accessible = prefs.getStringSet("accessible", emptySet()) ?: emptySet()
        if (never.isEmpty() && accessible.isEmpty()) {
            appsBox.addView(TextView(this).apply {
                textSize = 17f
                text = "No apps on the list yet. Game Bypass stays out of apps you can already use and remembers them here. You can also use Add an app to pick apps yourself."
            })
            return
        }
        for (pkg in never.sortedBy { label(it).lowercase() }) {
            appsBox.addView(Button(this).apply {
                text = "${label(pkg)}: Game Bypass stays off. Double tap to remove it from this list."
                isAllCaps = false
                setOnClickListener {
                    prefs.edit().putStringSet("never", never - pkg).apply()
                    showApps()
                }
            })
        }
        for (pkg in accessible.sortedBy { label(it).lowercase() }) {
            appsBox.addView(Button(this).apply {
                text = "${label(pkg)}: already accessible, Game Bypass stays off. Double tap to always use Game Bypass here."
                isAllCaps = false
                setOnClickListener {
                    val always = prefs.getStringSet("always", emptySet()) ?: emptySet()
                    prefs.edit()
                        .putStringSet("accessible", accessible - pkg)
                        .putStringSet("always", always + pkg)
                        .apply()
                    showApps()
                }
            })
        }
    }

    /**
     * Shows a list of the apps on your phone. The one you pick goes on the
     * "Game Bypass stays off" list.
     */
    private fun pickAppToExclude() {
        val prefs = getSharedPreferences("apps", MODE_PRIVATE)
        val never = prefs.getStringSet("never", emptySet()) ?: emptySet()
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != packageName && it !in never }
            .map { pkg ->
                pkg to runCatching {
                    packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
                }.getOrDefault(pkg)
            }
            .sortedBy { it.second.lowercase() }
        if (apps.isEmpty()) {
            AlertDialog.Builder(this)
                .setMessage("There are no more apps to add.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Choose an app where Game Bypass stays off")
            .setItems(apps.map { it.second }.toTypedArray()) { _, which ->
                val pkg = apps[which].first
                val always = prefs.getStringSet("always", emptySet()) ?: emptySet()
                prefs.edit()
                    .putStringSet("never", never + pkg)
                    .putStringSet("always", always - pkg)
                    .apply()
                showApps()
                window.decorView.announceForAccessibility("${apps[which].second} added. Game Bypass will stay off there.")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Speech engines installed on the phone: (package, name). */
    private fun engines(): List<Pair<String, String>> =
        packageManager.queryIntentServices(Intent(android.speech.tts.TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)
            .map { it.serviceInfo.packageName to it.loadLabel(packageManager).toString() }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }

    private fun showEngine() {
        val chosen = getSharedPreferences("voice", MODE_PRIVATE).getString("engine", "") ?: ""
        val name = if (chosen.isEmpty()) "Phone default"
        else engines().firstOrNull { it.first == chosen }?.second ?: chosen
        engineButton.text = "Speech engine: $name. Double tap to change."
    }

    /** Lets you pick which speech engine says the helper's announcements. */
    private fun pickEngine() {
        val list = listOf("" to "Phone default") + engines()
        AlertDialog.Builder(this)
            .setTitle("Speech engine for announcements")
            .setItems(list.map { it.second }.toTypedArray()) { _, which ->
                getSharedPreferences("voice", MODE_PRIVATE).edit()
                    .putString("engine", list[which].first).apply()
                showEngine()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun currentBuild(): Int =
        packageManager.getPackageInfo(packageName, 0).longVersionCode.toInt()

    private fun checkForUpdates() {
        updateStatus.text = "Checking for updates."
        Thread {
            try {
                val l = Updater.fetchLatest()
                runOnUiThread {
                    latest = l
                    if (l.build > currentBuild()) {
                        updateStatus.text = "Update available: build ${l.build}. You have build ${currentBuild()}. Double tap Install update."
                        updateButton.text = "Install update"
                    } else {
                        updateStatus.text = "You're up to date. Build ${currentBuild()}."
                        updateButton.text = "Check for updates"
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { updateStatus.text = "Couldn't check for updates. ${e.message}" }
            }
        }.start()
    }

    private fun downloadAndInstall(release: Updater.Release) {
        if (!packageManager.canRequestPackageInstalls()) {
            updateStatus.text = "First, allow this app to install updates. Turn on Allow from this source, then come back and double tap Install update again."
            startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
            )
            return
        }
        updateStatus.text = "Downloading build ${release.build}. This can take a minute."
        updateButton.isEnabled = false
        Thread {
            try {
                val file = File(cacheDir, "update.apk")
                Updater.download(release.apkUrl, file)
                runOnUiThread { updateStatus.text = "Downloaded. Android will ask you to confirm the update." }
                Updater.install(this, file)
            } catch (e: Exception) {
                runOnUiThread { updateStatus.text = "Update failed. ${e.message}" }
            } finally {
                runOnUiThread { updateButton.isEnabled = true }
            }
        }.start()
    }
}
