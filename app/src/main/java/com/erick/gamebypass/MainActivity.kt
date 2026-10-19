package com.erick.gamebypass

import android.app.Activity
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
                Keep TalkBack on. When you open a game TalkBack can't read, you'll hear Game Bypass Mode on, and how many items it found. Swipe through them, or slide your finger around the screen. Double tap an item to press it in the game.

                At the bottom of the screen there are three buttons: Rescan, Direct touch, and Pause.
                Rescan reads the screen again.
                Direct touch sends your taps straight to the game. Turn it off with the same button.
                While direct touch is on, it keeps watching the screen and says any new words the game shows, like Swipe right to dodge, so you can just do it.
                Pause turns Game Bypass Mode off for this game until you leave it.

                If it finds no text three times in a row, it switches to direct touch by itself, and switches back when a menu with text shows up.

                When you go home, it turns off and TalkBack works normally.
            """.trimIndent()
        })

        layout.addView(Button(this).apply {
            text = "Open Accessibility Settings"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        })

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
