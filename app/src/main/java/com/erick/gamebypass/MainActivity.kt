package com.erick.gamebypass

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** The simple screen you see when you open the app from the app list. */
class MainActivity : Activity() {
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

        setContentView(ScrollView(this).apply { addView(layout) })
    }
}
