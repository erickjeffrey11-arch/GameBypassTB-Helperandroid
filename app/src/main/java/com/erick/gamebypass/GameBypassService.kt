package com.erick.gamebypass

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.content.pm.PackageManager
import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.media.AudioAttributes
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.media.ToneGenerator
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Region
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.erick.gamebypass.HearthstoneBoard.Kind as K
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.max
import kotlin.math.min

/**
 * Game Bypass Mode.
 *
 * Plain-language summary of what this file does:
 *  1. Watches which app is in front.
 *  2. If that app shows TalkBack (almost) nothing, it turns Game Bypass Mode on.
 *  3. It takes a screenshot, reads the words with OCR, and places invisible
 *     "pretend buttons" exactly where those words are. TalkBack can swipe to them,
 *     and explore-by-touch finds them under your finger.
 *  4. Double tapping a pretend button taps that spot in the real game.
 *  5. Direct touch lets your own taps go straight to the game.
 *  6. When you leave the game (home or a normal app) it turns itself off.
 */
class GameBypassService : AccessibilityService() {

    private data class Item(val text: String, val rect: Rect)

    private val tag = "GameBypass"
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    // Apps we never treat as games.
    private val ignoredPackages = mutableSetOf(
        "com.android.systemui",
        "com.google.android.marvin.talkback",
        "com.android.talkback",
        "com.google.android.inputmethod.latin",
    )

    private var currentPackage: String? = null
    private var pausedPackage: String? = null
    private var bypassOn = false
    private var directTouch = false
    private var directTouchAuto = false
    private var scanning = false
    private var tapping = false
    private var emptyScans = 0
    private var currentTexts: Set<String> = emptySet()
    private var checkPending = false
    private var focusedRect: Rect? = null
    private var lastTapAt = 0L
    private var scaleX = 1f
    private var scaleY = 1f
    private var focusedView: View? = null

    // Game profiles. Only Hearthstone has one so far; every other game uses the normal behavior.
    private val hearthstonePackage = "com.blizzard.wtcg.hearthstone"
    private var profile: String? = null
    private var latestItems: List<Item> = emptyList()

    // Hearthstone match state.
    private var hsWords: List<HearthstoneBoard.Word> = emptyList()
    private var hsEntries: List<HearthstoneBoard.Entry> = emptyList()
    private var hsSelected: HearthstoneBoard.Entry? = null
    private var hsSignature = ""
    private var hsInMatch = false
    private var hsEnemyTurn = false
    private var focusedKey: String? = null

    // Our own voice for direct touch. TalkBack stops talking whenever you touch
    // the screen; this voice doesn't, so you hear instructions while you play.
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    // Direct touch: words already spoken, and when, so we don't repeat them.
    private val spokenAt = mutableMapOf<String, Long>()

    // The two invisible windows we put on top of the game.
    private var itemsLayer: FrameLayout? = null
    private var itemsParams: WindowManager.LayoutParams? = null
    private val itemsOffset = IntArray(2)
    private var controlBar: LinearLayout? = null
    private var statusText: TextView? = null
    private var directButton: Button? = null
    private val removeBarRunnable = Runnable { removeControlBar() }

    private val periodicScan = object : Runnable {
        override fun run() {
            if (!bypassOn) return
            scan()
            // Look more often in direct touch, so game instructions are spoken quickly.
            handler.postDelayed(this, if (directTouch) 1500 else 3000)
        }
    }

    private val checkRunnable = Runnable {
        checkPending = false
        checkForeground()
    }

    // ---------------------------------------------------------------- setup

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        ignoredPackages.add(packageName)
        ignoredPackages.addAll(findHomePackages())
        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
            }
        }
        Log.i(tag, "Connected. Ignoring: $ignoredPackages")
    }

    private fun findHomePackages(): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .mapNotNull { it.activityInfo?.packageName }
            .toSet()
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        tts?.shutdown()
        tts = null
        turnOff(announce = false)
        removeControlBar()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ------------------------------------------------- noticing the game

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.packageName?.toString() == packageName) return
        // Wait a moment so loading screens can settle, and don't check too often.
        if (!checkPending) {
            checkPending = true
            handler.postDelayed(checkRunnable, 800)
        }
    }

    /** Finds the app window that is in front, ignoring keyboards, system bars and our own windows. */
    private fun frontAppRoot(): AccessibilityNodeInfo? {
        val appWindows = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val win = appWindows.firstOrNull { it.isActive }
            ?: appWindows.firstOrNull { it.isFocused }
            ?: appWindows.firstOrNull()
        return win?.root
    }

    private fun checkForeground() {
        val root = frontAppRoot() ?: return
        val pkg = root.packageName?.toString() ?: return
        if (pkg == packageName) return

        if (pkg != currentPackage) {
            currentPackage = pkg
            pausedPackage = null
            // Went straight from one game to another: start fresh so the right profile loads.
            if (bypassOn) turnOff(announce = false)
            profile = if (pkg == hearthstonePackage) "hearthstone" else null
        }

        // Hearthstone always uses Game Bypass Mode, even if it shows TalkBack a little text.
        val unreadable = pkg !in ignoredPackages && (profile == "hearthstone" || isUnreadable(root))
        Log.i(tag, "Front app $pkg unreadable=$unreadable bypass=$bypassOn")

        if (unreadable && !bypassOn && pausedPackage != pkg) {
            turnOn()
        } else if (!unreadable && bypassOn) {
            turnOff(announce = true)
        }
    }

    /** "Unreadable" means TalkBack would find at most one thing with words on it. */
    private fun isUnreadable(root: AccessibilityNodeInfo): Boolean {
        var readable = 0
        var visited = 0
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        while (stack.isNotEmpty() && visited < 400) {
            val node = stack.removeLast()
            visited++
            if (node.isVisibleToUser) {
                val hasWords = !node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank()
                if (hasWords) {
                    readable++
                    if (readable > 1) return false
                }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { stack.add(it) }
        }
        return true
    }

    // ---------------------------------------------------- on and off

    private fun turnOn() {
        bypassOn = true
        directTouch = false
        directTouchAuto = false
        emptyScans = 0
        currentTexts = emptySet()
        handler.removeCallbacks(removeBarRunnable)
        removeControlBar()
        showItemsLayer()
        showControlBar()
        setStatus(
            if (profile == "hearthstone") "Game Bypass Mode on. Hearthstone profile."
            else "Game Bypass Mode on. Reading the screen."
        )
        handler.removeCallbacks(periodicScan)
        handler.postDelayed(periodicScan, 400)
    }

    private fun turnOff(announce: Boolean) {
        if (!bypassOn) return
        bypassOn = false
        handler.removeCallbacks(periodicScan)
        setDirectTouch(false, auto = false, speak = false)
        removeItemsLayer()
        if (announce) {
            setStatus("Game Bypass Mode off.")
            // Give TalkBack time to speak before the bar disappears.
            handler.postDelayed(removeBarRunnable, 1500)
        } else {
            removeControlBar()
        }
    }

    // ------------------------------------------------- invisible windows

    private fun overlayParams(width: Int, height: Int, gravity: Int, extraFlags: Int) =
        WindowManager.LayoutParams(
            width, height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or extraFlags,
            PixelFormat.TRANSLUCENT
        ).apply {
            this.gravity = gravity
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

    private fun showItemsLayer() {
        if (itemsLayer != null) return
        val layer = FrameLayout(this)
        layer.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        val params = overlayParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            Gravity.TOP or Gravity.START,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        try {
            wm.addView(layer, params)
            itemsLayer = layer
            itemsParams = params
            layer.post { layer.getLocationOnScreen(itemsOffset) }
        } catch (e: Exception) {
            Log.e(tag, "Couldn't add items layer", e)
        }
    }

    private fun removeItemsLayer() {
        itemsLayer?.let { runCatching { wm.removeView(it) } }
        itemsLayer = null
        itemsParams = null
        currentTexts = emptySet()
    }

    private fun setItemsTouchable(touchable: Boolean) {
        val layer = itemsLayer ?: return
        val params = itemsParams ?: return
        params.flags = if (touchable) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        runCatching { wm.updateViewLayout(layer, params) }
    }

    private fun invisibleButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        // See-through so it doesn't cover the game or get picked up by the OCR.
        setTextColor(Color.TRANSPARENT)
        setBackgroundColor(Color.TRANSPARENT)
        isAllCaps = false
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }

    private fun showControlBar() {
        if (controlBar != null) return
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        val status = TextView(this).apply {
            setTextColor(Color.TRANSPARENT)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        bar.addView(status)
        bar.addView(invisibleButton("Rescan") {
            currentTexts = emptySet()
            emptyScans = 0
            setStatus("Rescanning.")
            scan()
        })
        val direct = invisibleButton("Direct touch, off") {
            setDirectTouch(!directTouch, auto = false, speak = true)
        }
        bar.addView(direct)
        if (profile == "hearthstone") {
            bar.addView(invisibleButton("End turn") { endTurn() })
            bar.addView(invisibleButton("Save screen for help") { saveScreenForHelp() })
        }
        bar.addView(invisibleButton("Pause Game Bypass for this game") {
            pausedPackage = currentPackage
            turnOff(announce = true)
        })

        val params = overlayParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM,
            0
        )
        try {
            wm.addView(bar, params)
            controlBar = bar
            statusText = status
            directButton = direct
        } catch (e: Exception) {
            Log.e(tag, "Couldn't add control bar", e)
        }
    }

    private fun removeControlBar() {
        handler.removeCallbacks(removeBarRunnable)
        controlBar?.let { runCatching { wm.removeView(it) } }
        controlBar = null
        statusText = null
        directButton = null
    }

    /** Changing this text makes TalkBack speak it (it's a "live region"). */
    private fun setStatus(message: String) {
        Log.i(tag, "Status: $message")
        statusText?.let { tv -> tv.post { tv.text = message } }
    }

    private fun controlBarRect(): Rect? {
        val bar = controlBar ?: return null
        if (bar.width == 0) return null
        val loc = IntArray(2)
        bar.getLocationOnScreen(loc)
        return Rect(loc[0], loc[1], loc[0] + bar.width, loc[1] + bar.height)
    }

    // ------------------------------------------------------ direct touch

    private fun setDirectTouch(on: Boolean, auto: Boolean, speak: Boolean) {
        if (on == directTouch && !speak) return
        directTouch = on
        directTouchAuto = on && auto
        directButton?.text = if (on) "Direct touch, on" else "Direct touch, off"

        try {
            val info = serviceInfo
            info.flags = if (on) {
                info.flags or AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
            } else {
                info.flags and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE.inv()
            }
            serviceInfo = info

            if (on) {
                val screen = wm.currentWindowMetrics.bounds
                val region = Region(screen)
                controlBarRect()?.let { region.op(it, Region.Op.DIFFERENCE) }
                setTouchExplorationPassthroughRegion(Display.DEFAULT_DISPLAY, region)
            } else {
                setTouchExplorationPassthroughRegion(Display.DEFAULT_DISPLAY, Region())
            }
        } catch (e: Exception) {
            Log.e(tag, "Direct touch failed", e)
            if (speak) setStatus("Direct touch didn't work: ${e.javaClass.simpleName}.")
            return
        }

        if (on) {
            // Let touches fall through our invisible items to the game.
            itemsLayer?.removeAllViews()
            currentTexts = emptySet()
            setItemsTouchable(false)
        } else {
            setItemsTouchable(true)
        }

        if (speak) {
            setStatus(
                when {
                    on && auto -> "No text found. Direct touch on. The bottom strip still works with TalkBack."
                    on -> "Direct touch on. The bottom strip still works with TalkBack."
                    else -> "Direct touch off. Reading mode."
                }
            )
            if (!on) { currentTexts = emptySet(); scan() }
        }
    }

    // ------------------------------------------------------- reading

    private fun scan() {
        if (!bypassOn || scanning || tapping) return
        scanning = true
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val buffer = result.hardwareBuffer
                    val hw = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                    val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    hw?.recycle()
                    buffer.close()
                    if (bmp == null) { scanning = false; return }
                    // Make sure picture positions match screen positions exactly.
                    val screen = wm.currentWindowMetrics.bounds
                    scaleX = screen.width().toFloat() / bmp.width
                    scaleY = screen.height().toFloat() / bmp.height
                    // Read twice: once normally, once as a high-contrast black and white
                    // picture. Fancy game lettering (gold, glowing, carved) often only
                    // becomes readable in the second one.
                    val contrast = highContrast(bmp)
                    recognizer.process(InputImage.fromBitmap(bmp, 0))
                        .addOnSuccessListener { first ->
                            recognizer.process(InputImage.fromBitmap(contrast, 0))
                                .addOnSuccessListener { second ->
                                    scanning = false
                                    bmp.recycle(); contrast.recycle()
                                    onTextFound(listOf(first, second))
                                }
                                .addOnFailureListener { e ->
                                    scanning = false
                                    bmp.recycle(); contrast.recycle()
                                    Log.e(tag, "Second OCR pass failed", e)
                                    onTextFound(listOf(first))
                                }
                        }
                        .addOnFailureListener { e ->
                            scanning = false
                            bmp.recycle(); contrast.recycle()
                            Log.e(tag, "OCR failed", e)
                        }
                }

                override fun onFailure(errorCode: Int) {
                    scanning = false
                    Log.w(tag, "Screenshot failed: $errorCode")
                    if (errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                        handler.postDelayed({ scan() }, 600)
                    } else if (errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW) {
                        setStatus("This app blocks screenshots, so it can't be read. Try direct touch.")
                    }
                }
            })
        } catch (e: Exception) {
            scanning = false
            Log.e(tag, "takeScreenshot threw", e)
        }
    }

    private fun onTextFound(results: List<Text>) {
        if (!bypassOn) return
        val bar = controlBarRect()
        val screenH = wm.currentWindowMetrics.bounds.height()
        data class Found(val item: Item, val confidence: Float)
        val found = results.flatMap { r -> r.textBlocks.flatMap { it.lines } }
            .mapNotNull { line ->
                val raw = line.boundingBox ?: return@mapNotNull null
                val box = Rect(
                    (raw.left * scaleX).toInt(), (raw.top * scaleY).toInt(),
                    (raw.right * scaleX).toInt(), (raw.bottom * scaleY).toInt()
                )
                val words = cleanText(line.text) ?: return@mapNotNull null
                // The reader's own "how sure am I" score. Low = probably game art, not words.
                // Big text is usually a real button, so it gets a gentler check.
                val big = box.height() > screenH * 0.03f
                val minConfidence = if (big) 0.3f else 0.55f
                if (line.confidence in 0f..minConfidence) return@mapNotNull null
                // Tilted text is almost always part of a picture.
                if (kotlin.math.abs(line.angle) > 25f) return@mapNotNull null
                // Tiny specks.
                if (box.height() < 12) return@mapNotNull null
                if (bar != null && Rect.intersects(bar, box) && box.centerY() > bar.top) return@mapNotNull null
                Found(Item(words, box), line.confidence)
            }
        // Both reading passes often find the same words. Keep the most confident one.
        val kept = mutableListOf<Found>()
        for (f in found.sortedByDescending { it.confidence }) {
            val dup = kept.any { k -> overlapRatio(k.item.rect, f.item.rect) > 0.5f }
            if (!dup) kept.add(f)
        }
        val lines = kept.map { it.item }
        val items = orderForReading(mergeNearbyLines(lines))
        val texts = items.map { it.text.lowercase() }.toSet()

        latestItems = items
        if (items.isEmpty()) {
            emptyScans++
            // Hearthstone never needs automatic direct touch.
            if (emptyScans == 3 && !directTouch && profile != "hearthstone") {
                setDirectTouch(true, auto = true, speak = true)
            }
            return
        }
        emptyScans = 0

        if (profile == "hearthstone" && !directTouch && handleHearthstone(items)) return

        if (directTouch) {
            // A menu with words showed up again: go back to reading mode, but only
            // if direct touch was switched on automatically.
            if (directTouchAuto && items.size >= 3) {
                setDirectTouch(false, auto = false, speak = true)
            } else {
                speakNewGameText(items)
            }
            return
        }

        if (similarity(texts, currentTexts) >= 0.7) return // same screen, keep TalkBack's place
        val firstTime = currentTexts.isEmpty()
        currentTexts = texts
        buildItems(items)
        val count = "${items.size} item${if (items.size == 1) "" else "s"}"
        setStatus(if (firstTime) "Game Bypass Mode. $count." else "Screen changed. $count.")
    }

    /**
     * In direct touch, say any new words the game shows, like "Swipe right to dodge",
     * so you can just do it. Numbers are ignored when checking for "new", so a
     * score going from 120 to 130 doesn't keep talking.
     */
    private fun speakNewGameText(items: List<Item>, prefix: String = "") {
        val now = System.currentTimeMillis()
        spokenAt.entries.removeAll { now - it.value > 15000 }
        val fresh = items.filter { item ->
            val key = item.text.lowercase().replace(Regex("[0-9]+"), "#").trim()
            val hasLetters = key.any { it.isLetter() }
            if (!hasLetters || key.length < 2 || spokenAt.containsKey(key)) {
                if (hasLetters) spokenAt[key] = now // still on screen: keep it quiet
                false
            } else {
                spokenAt[key] = now
                true
            }
        }
        if (fresh.isNotEmpty()) {
            speakGame(prefix + fresh.take(3).joinToString(". ") { it.text })
        }
    }

    /** Speaks with our own voice, which your taps can't interrupt. Falls back to TalkBack. */
    private fun speakGame(text: String) {
        val engine = tts
        if (engine != null && ttsReady) {
            engine.speak(text, TextToSpeech.QUEUE_ADD, null, "game-${System.nanoTime()}")
        } else {
            setStatus(text)
        }
    }

    /**
     * Junk text cleanup. Returns cleaned words, or null if it's junk.
     * Junk = stray symbols, single letters, lines that are mostly symbols,
     * and long runs of the same character (like "lllll" from a fence or border).
     */
    private fun cleanText(raw: String): String? {
        // Remove symbols that are almost never real words in a game menu.
        var t = raw.replace(Regex("[|`~^_=<>\\\\{}\\[\\]•·°¦]"), " ")
        t = t.replace(Regex("\\s+"), " ").trim()
        // Trim leftover punctuation from the ends, like "- Play ." -> "Play".
        t = t.trim { !it.isLetterOrDigit() && it != '!' && it != '?' && it != '%' && it != '$' }
        if (t.isEmpty()) return null
        val useful = t.count { it.isLetterOrDigit() }
        if (useful == 0) return null
        // Mostly symbols.
        if (useful.toFloat() / t.length < 0.6f) return null
        // A single letter alone is almost always noise (a single number is kept: levels, counts).
        if (t.length == 1 && !t[0].isDigit()) return null
        // Two letters with no vowel and not all capitals, like "lj" or "rn".
        if (t.length == 2 && t.all { it.isLetter() } && t.none { it.lowercaseChar() in "aeiouy" } &&
            !t.all { it.isUpperCase() }
        ) return null
        // Same character repeated 4+ times, like "IIIII" or "-----".
        if (Regex("([^0-9])\\1{3,}").containsMatchIn(t)) return null
        return t
    }

    /** How much two boxes overlap, compared to the smaller one (0 = not at all, 1 = fully). */
    private fun overlapRatio(a: Rect, b: Rect): Float {
        val i = Rect()
        if (!i.setIntersect(a, b)) return 0f
        val smaller = min(a.width() * a.height(), b.width() * b.height()).coerceAtLeast(1)
        return (i.width() * i.height()).toFloat() / smaller
    }

    /** Black and white with strong contrast, which helps with fancy game lettering. */
    private fun highContrast(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c = 1.8f
        val t = (-0.5f * c + 0.5f) * 255f
        val matrix = ColorMatrix().apply { setSaturation(0f) }
        matrix.postConcat(ColorMatrix(floatArrayOf(
            c, 0f, 0f, 0f, t,
            0f, c, 0f, 0f, t,
            0f, 0f, c, 0f, t,
            0f, 0f, 0f, 1f, 0f
        )))
        Canvas(out).drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(matrix) })
        return out
    }

    /**
     * Hearthstone's End turn button. Uses the words on the button when they can be read;
     * otherwise taps where the button sits on Hearthstone's screen (right side, middle).
     */
    private fun endTurn() {
        val enemy = latestItems.firstOrNull { it.text.lowercase().contains("enemy turn") }
        if (enemy != null) {
            setStatus("It's the enemy's turn.")
            return
        }
        val button = latestItems.firstOrNull {
            Regex("end\\s*turn|your\\s*turn").containsMatchIn(it.text.lowercase())
        }
        if (button != null) {
            tapAt(button.rect)
            return
        }
        val screen = wm.currentWindowMetrics.bounds
        val w = max(screen.width(), screen.height())
        val h = min(screen.width(), screen.height())
        val x = (w * 0.885f).toInt()
        val y = (h * 0.46f).toInt()
        tapAt(Rect(x - 40, y - 20, x + 40, y + 20))
    }

    // ------------------------------------------------------ Hearthstone

    /** Returns true if this was a Hearthstone match screen and it was handled. */
    private fun handleHearthstone(items: List<Item>): Boolean {
        val words = items.map { HearthstoneBoard.Word(it.text, it.rect) }
        val b = wm.currentWindowMetrics.bounds
        val entries = HearthstoneBoard.build(words, b.width(), b.height(), hsSelected)
        if (entries == null) {
            // Menus, deck builder, shop: normal reading.
            if (hsInMatch) {
                hsInMatch = false
                hsSelected = null
                hsSignature = ""
                currentTexts = emptySet()
            }
            return false
        }
        hsWords = words
        if (!hsInMatch) {
            hsInMatch = true
            currentTexts = emptySet()
            speakGame("Match. Use headings to jump between your hand, your minions, opponent's minions, heroes, and other.")
        }
        val enemy = HearthstoneBoard.isEnemyTurn(words)
        if (enemy != hsEnemyTurn) {
            hsEnemyTurn = enemy
            if (enemy) hsSelected = null
            speakGame(if (enemy) "Opponent's turn." else "Your turn.")
        }
        // Opponent's log: during their turn, say the new words that pop up,
        // like the name of a card they just played.
        if (enemy) {
            speakNewGameText(
                items.filter { item ->
                    item.text.any { it.isLetter() } && item.text.length > 3 &&
                        !Regex("enemy\\s*turn|end\\s*turn|your\\s*turn").containsMatchIn(item.text.lowercase())
                },
                prefix = "Opponent: "
            )
        }
        val sig = entries.joinToString("|") { it.label }
        if (sig != hsSignature) {
            hsSignature = sig
            hsEntries = entries
            buildHearthstone(entries)
        }
        return true
    }

    private fun rebuildHearthstone() {
        val b = wm.currentWindowMetrics.bounds
        val entries = HearthstoneBoard.build(hsWords, b.width(), b.height(), hsSelected) ?: return
        hsEntries = entries
        hsSignature = entries.joinToString("|") { it.label }
        buildHearthstone(entries)
    }

    private fun buildHearthstone(entries: List<HearthstoneBoard.Entry>) {
        val layer = itemsLayer ?: return
        layer.removeAllViews()
        val pad = (4 * resources.displayMetrics.density).toInt()
        var refocus: View? = null
        for (e in entries) {
            val r = e.rect
            val isHeading = e.kind == HearthstoneBoard.Kind.HEADING
            val view = View(this).apply {
                contentDescription = e.label
                isFocusable = true
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                isAccessibilityHeading = isHeading
                if (!isHeading) {
                    isClickable = true
                    setOnClickListener { activateHearthstone(e) }
                }
                accessibilityDelegate = object : View.AccessibilityDelegate() {
                    override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        if (!isHeading) info.className = "android.widget.Button"
                    }

                    override fun performAccessibilityAction(host: View, action: Int, args: android.os.Bundle?): Boolean {
                        when (action) {
                            AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS -> {
                                focusedKey = e.key
                                focusedView = host
                            }
                            AccessibilityNodeInfo.ACTION_CLICK -> {
                                if (!isHeading) activateHearthstone(e)
                                return true
                            }
                        }
                        return super.performAccessibilityAction(host, action, args)
                    }
                }
            }
            val lp = FrameLayout.LayoutParams(
                max(r.width(), 8) + pad * 2, max(r.height(), 8) + pad * 2
            ).apply {
                leftMargin = r.left - pad - itemsOffset[0]
                topMargin = r.top - pad - itemsOffset[1]
            }
            layer.addView(view, lp)
            if (e.key == focusedKey) refocus = view
        }
        // Keep TalkBack on the same thing after the list changes.
        refocus?.let { v -> v.post { v.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null) } }
    }

    private fun shortName(e: HearthstoneBoard.Entry) = e.label.substringBefore(',')

    /** Select, then confirm. Everything in a Hearthstone match goes through here. */
    private fun activateHearthstone(e: HearthstoneBoard.Entry) {
        val now = System.currentTimeMillis()
        if (now - lastTapAt < 400) return // one double tap can arrive twice

        val sel = hsSelected
        fun select(message: String) {
            lastTapAt = now
            hsSelected = e
            setStatus(message)
            rebuildHearthstone()
        }
        fun cancel() {
            lastTapAt = now
            hsSelected = null
            setStatus("Cancelled.")
            rebuildHearthstone()
        }
        fun move(from: HearthstoneBoard.Entry, to: Rect, message: String) {
            hsSelected = null
            setStatus(message)
            dragFrom(from.rect, to)
        }
        when (e.kind) {
            K.HEADING -> {}
            K.OTHER -> tapAt(e.rect)
            K.CANCEL -> cancel()
            K.HAND_CARD -> when {
                sel?.key == e.key -> {
                    // Second double tap on the same card: play it (at the right end of your minions).
                    val spot = hsEntries.firstOrNull { it.key == "drop-right" || it.key == "drop-center" }?.rect
                        ?: Rect(0, 0, 0, 0).also { r ->
                            val b = wm.currentWindowMetrics.bounds
                            r.set(b.width() / 2 - 20, (b.height() * 0.56f).toInt() - 20, b.width() / 2 + 20, (b.height() * 0.56f).toInt() + 20)
                        }
                    move(e, spot, "Playing ${shortName(e)}.")
                }
                else -> select("${shortName(e)}. Summon? Double tap again to play it, or choose a spot or a target.")
            }
            K.MY_MINION, K.MY_HERO -> when {
                sel?.key == e.key -> cancel()
                sel != null && (sel.kind == K.HAND_CARD || sel.kind == K.HERO_POWER) ->
                    move(sel, e.rect, "${shortName(sel)} on ${shortName(e)}.")
                else -> select("${shortName(e)} selected. Choose a target.")
            }
            K.ENEMY_MINION, K.ENEMY_HERO -> {
                if (sel == null) {
                    setStatus("Select one of your minions, a card, or your Hero Power first.")
                } else {
                    val verb = if (sel.kind == K.MY_MINION || sel.kind == K.MY_HERO) "attacks" else "on"
                    move(sel, e.rect, "${shortName(sel)} $verb ${shortName(e)}.")
                }
            }
            K.HERO_POWER -> when {
                sel?.key == e.key -> {
                    hsSelected = null
                    setStatus("Using Hero Power.")
                    tapAt(e.rect)
                }
                else -> select("Hero Power selected. Double tap again to use it, or choose a target.")
            }
            K.DROP -> {
                if (sel == null) setStatus("Select a card first.")
                else move(sel, e.rect, "${e.label.replace("Summon", "Summoning ${shortName(sel)}")}.")
            }
        }
    }

    /** Drags a finger from one spot to another, like playing a card or attacking. */
    private fun dragFrom(from: Rect, to: Rect) {
        lastTapAt = System.currentTimeMillis()
        tapping = true
        itemsLayer?.let { runCatching { wm.removeViewImmediate(it) } }
        openPassthroughSpot(wm.currentWindowMetrics.bounds)
        val path = Path().apply {
            moveTo(from.exactCenterX(), from.exactCenterY())
            lineTo(to.exactCenterX(), to.exactCenterY())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 450))
            .build()
        handler.postDelayed({
            val sent = dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) { beep(ok = true); afterTap() }
                override fun onCancelled(g: GestureDescription?) {
                    beep(ok = false); setStatus("The move was cancelled by Android."); afterTap()
                }
            }, null)
            if (!sent) { beep(ok = false); setStatus("Android didn't allow the move."); afterTap() }
        }, 50)
    }

    /**
     * Saves a screenshot and what the app read into Downloads, GameBypass folder,
     * so you can send them to Claude to tune the Hearthstone layout.
     */
    private fun saveScreenForHelp() {
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val buffer = result.hardwareBuffer
                    val hw = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                    val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    hw?.recycle(); buffer.close()
                    if (bmp == null) { setStatus("Couldn't save the screen."); return }
                    val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
                    try {
                        writeDownload("gamebypass-$stamp.png", "image/png") { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        val b = wm.currentWindowMetrics.bounds
                        val report = buildString {
                            appendLine("screen ${b.width()}x${b.height()} screenshot ${bmp.width}x${bmp.height}")
                            latestItems.forEach { appendLine("${it.text}\t${it.rect.left},${it.rect.top},${it.rect.right},${it.rect.bottom}") }
                            appendLine("--- entries")
                            hsEntries.forEach { appendLine("${it.kind}\t${it.label}\t${it.rect.left},${it.rect.top},${it.rect.right},${it.rect.bottom}") }
                        }
                        writeDownload("gamebypass-$stamp.txt", "text/plain") { it.write(report.toByteArray()) }
                        setStatus("Saved to Downloads, GameBypass folder.")
                    } catch (e: Exception) {
                        setStatus("Couldn't save the screen.")
                        Log.e(tag, "Save failed", e)
                    } finally {
                        bmp.recycle()
                    }
                }
                override fun onFailure(errorCode: Int) { setStatus("Couldn't take the screenshot. Try again in a second.") }
            })
        } catch (e: Exception) {
            setStatus("Couldn't save the screen.")
        }
    }

    private fun writeDownload(name: String, mime: String, write: (java.io.OutputStream) -> Unit) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/GameBypass")
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("No download folder")
        contentResolver.openOutputStream(uri)!!.use(write)
    }

    private fun similarity(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return a.intersect(b).size.toDouble() / max(a.size, b.size)
    }

    /** Joins lines that are stacked right on top of each other, like a two-line button label. */
    private fun mergeNearbyLines(lines: List<Item>): List<Item> {
        val sorted = lines.sortedBy { it.rect.top }.toMutableList()
        val merged = mutableListOf<Item>()
        for (line in sorted) {
            val prev = merged.lastOrNull()
            if (prev != null) {
                val overlap = min(prev.rect.right, line.rect.right) - max(prev.rect.left, line.rect.left)
                val narrower = min(prev.rect.width(), line.rect.width())
                val gap = line.rect.top - prev.rect.bottom
                val lineHeight = min(prev.rect.height(), line.rect.height())
                if (overlap > narrower * 0.5 && gap in -lineHeight / 2..(lineHeight * 0.6).toInt()) {
                    val r = Rect(prev.rect).apply { union(line.rect) }
                    merged[merged.lastIndex] = Item(prev.text + " " + line.text, r)
                    continue
                }
            }
            merged.add(line)
        }
        return merged
    }

    /** Top to bottom, and left to right within a row. */
    private fun orderForReading(items: List<Item>): List<Item> {
        val byY = items.sortedBy { it.rect.centerY() }
        val rows = mutableListOf<MutableList<Item>>()
        for (item in byY) {
            val row = rows.lastOrNull()
            if (row != null && kotlin.math.abs(row.first().rect.centerY() - item.rect.centerY()) <
                max(row.first().rect.height(), item.rect.height()) / 2
            ) row.add(item) else rows.add(mutableListOf(item))
        }
        return rows.flatMap { row -> row.sortedBy { it.rect.left } }
    }

    // ---------------------------------------------- pretend buttons

    private fun buildItems(items: List<Item>) {
        val layer = itemsLayer ?: return
        layer.removeAllViews()
        focusedRect = null
        val pad = (6 * resources.displayMetrics.density).toInt()
        for (item in items) {
            val r = item.rect
            val view = View(this).apply {
                contentDescription = item.text
                isFocusable = true
                isClickable = true
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                // Way 1: a normal click on the item.
                setOnClickListener { tapAt(r) }
                accessibilityDelegate = object : View.AccessibilityDelegate() {
                    override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        info.className = "android.widget.Button"
                    }

                    override fun performAccessibilityAction(host: View, action: Int, args: android.os.Bundle?): Boolean {
                        when (action) {
                            // Remember which item TalkBack is on.
                            AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS -> {
                                focusedRect = r
                                focusedView = host
                            }
                            // Way 2: TalkBack's double tap arrives as a "click" action. Tap the game.
                            AccessibilityNodeInfo.ACTION_CLICK -> {
                                tapAt(r)
                                return true
                            }
                        }
                        return super.performAccessibilityAction(host, action, args)
                    }
                }
            }
            val lp = FrameLayout.LayoutParams(r.width() + pad * 2, r.height() + pad * 2).apply {
                leftMargin = r.left - pad - itemsOffset[0]
                topMargin = r.top - pad - itemsOffset[1]
            }
            layer.addView(view, lp)
        }
    }

    /**
     * Taps the real game at the middle of the item you're focused on, right away.
     * Short beep = the tap was sent to the game. Low buzz = Android refused it.
     * (The beeps are just for testing, so we know where it goes wrong.)
     */
    /**
     * The pass-through trick (same feature direct touch uses): touches inside this spot
     * skip TalkBack and go straight to the game. Used for a split second during a double tap,
     * because otherwise TalkBack catches our fake tap and treats it as exploring.
     */
    private fun openPassthroughSpot(r: Rect): Boolean = try {
        val info = serviceInfo
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
        serviceInfo = info
        val spot = Rect(r).apply { inset(-40, -40) }
        val region = Region(spot)
        if (directTouch) {
            val screen = wm.currentWindowMetrics.bounds
            region.op(Region(screen), Region.Op.UNION)
            controlBarRect()?.let { region.op(it, Region.Op.DIFFERENCE) }
        }
        setTouchExplorationPassthroughRegion(Display.DEFAULT_DISPLAY, region)
        true
    } catch (e: Exception) {
        Log.e(tag, "Pass-through spot failed", e)
        false
    }

    /** Puts pass-through back the way it was: whole screen in direct touch, nothing otherwise. */
    private fun closePassthroughSpot() {
        try {
            if (directTouch) {
                val screen = wm.currentWindowMetrics.bounds
                val region = Region(screen)
                controlBarRect()?.let { region.op(it, Region.Op.DIFFERENCE) }
                setTouchExplorationPassthroughRegion(Display.DEFAULT_DISPLAY, region)
            } else {
                setTouchExplorationPassthroughRegion(Display.DEFAULT_DISPLAY, Region())
                val info = serviceInfo
                info.flags = info.flags and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE.inv()
                serviceInfo = info
            }
        } catch (e: Exception) {
            Log.e(tag, "Closing pass-through spot failed", e)
        }
    }

    private fun tapAt(r: Rect, attempt: Int = 1) {
        val now = System.currentTimeMillis()
        // Both ways can fire for one double tap; only tap once.
        if (attempt == 1 && now - lastTapAt < 400) return
        lastTapAt = now
        tapping = true
        // Take our invisible layer off the screen right now (not "soon"), so the
        // tap reaches the game and not our own pretend button.
        if (attempt == 1) {
            itemsLayer?.let { runCatching { wm.removeViewImmediate(it) } }
            openPassthroughSpot(r)
        }
        val path = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        // Hold the finger down a little longer; some games ignore very quick taps.
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 120))
            .build()
        // Tiny wait (a twentieth of a second) for Android to notice the layer is gone.
        handler.postDelayed({
            val sent = dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) {
                    Log.i(tag, "Tap done at ${r.centerX()},${r.centerY()}")
                    beep(ok = true)
                    afterTap()
                }
                override fun onCancelled(g: GestureDescription?) {
                    Log.w(tag, "Tap cancelled, attempt $attempt")
                    if (attempt < 2) handler.postDelayed({ tapAt(r, attempt + 1) }, 50) else {
                        beep(ok = false)
                        setStatus("The tap was cancelled by Android.")
                        afterTap()
                    }
                }
            }, null)
            if (!sent) {
                beep(ok = false)
                setStatus("Android didn't allow the tap.")
                afterTap()
            }
        }, 50)
    }

    private fun afterTap() {
        handler.postDelayed({
            closePassthroughSpot()
            tapping = false
            if (!bypassOn) return@postDelayed
            // Put the same invisible layer back, with the same items.
            val layer = itemsLayer
            val params = itemsParams
            if (layer != null && params != null && layer.parent == null) {
                if (directTouch) params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                runCatching { wm.addView(layer, params) }
                // Keep the bottom strip on top so you can still find it by touch.
                controlBar?.let { bar ->
                    val lp = bar.layoutParams
                    runCatching { wm.removeViewImmediate(bar); wm.addView(bar, lp) }
                }
                // Put TalkBack back on the item you were on.
                focusedView?.let { v ->
                    v.post { v.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null) }
                }
            }
            // Look again once the game has reacted.
            handler.postDelayed({ scan() }, 800)
        }, 150)
    }

    private val tones by lazy { runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 60) }.getOrNull() }

    private fun beep(ok: Boolean) {
        tones?.startTone(if (ok) ToneGenerator.TONE_PROP_ACK else ToneGenerator.TONE_PROP_NACK, 120)
    }
}
