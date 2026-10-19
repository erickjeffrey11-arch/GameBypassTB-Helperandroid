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

    private data class Item(val text: String, val rect: Rect, val node: AccessibilityNodeInfo? = null)

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
    private var unreadableSince: Long? = null
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
    // Set when you press Rescan, so the next reading always says how many buttons it found.
    private var rescanRequested = false
    // True while the keyboard is on screen.
    private var imeShowing = false

    // Smart labels (on-phone AI descriptions of weird or unlabeled buttons).
    private var lastShot: Bitmap? = null
    private val labeler by lazy { SmartLabeler(this, mainExecutor) }
    private val labelPrefs by lazy { getSharedPreferences("smart_labels", MODE_PRIVATE) }
    private val itemViews = mutableListOf<Pair<Item, View>>()
    private val labelQueue = ArrayDeque<Item>()
    private var describing = false
    private var labelerWarned = false
    private var focusedItem: Item? = null

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
        runCatching { labeler.close() }
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
        val ime = windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        if (ime != imeShowing) {
            imeShowing = ime
            if (bypassOn) onKeyboardChanged(ime)
        }
        val root = frontAppRoot() ?: return
        val pkg = root.packageName?.toString() ?: return
        if (pkg == packageName) return

        if (pkg != currentPackage) {
            currentPackage = pkg
            pausedPackage = null
            unreadableSince = null
            // Went straight from one game to another: start fresh so the right profile loads.
            if (bypassOn) turnOff(announce = false)
            profile = if (pkg == hearthstonePackage) "hearthstone" else null
        }

        val readable = countReadable(root)
        val game = isGame(pkg)
        // An app that shows TalkBack plenty of real buttons is accessible: remember it
        // and stay out of it from now on. Games are never marked, because their login
        // screens can be readable while the game itself isn't.
        if (readable >= 5 && !game && profile == null && pkg !in appList("always") && pkg !in ignoredPackages) {
            addToList("accessible", pkg)
        }

        val unreadable = when {
            pkg in ignoredPackages || pkg in appList("never") -> false
            profile == "hearthstone" || pkg in appList("always") -> true
            pkg in appList("accessible") -> false
            else -> readable <= 1
        }
        Log.i(tag, "Front app $pkg readable=$readable game=$game unreadable=$unreadable bypass=$bypassOn")

        if (unreadable && !bypassOn && pausedPackage != pkg) {
            val instant = profile == "hearthstone" || pkg in appList("always")
            // Patience: the screen has to stay unreadable for a moment, so loading
            // screens and splash logos don't turn it on.
            val wait = if (game) 1500L else 3000L
            val now = System.currentTimeMillis()
            val since = unreadableSince ?: now.also { unreadableSince = it }
            if (instant || now - since >= wait) {
                turnOn()
            } else {
                handler.removeCallbacks(checkRunnable)
                checkPending = true
                handler.postDelayed(checkRunnable, wait - (now - since) + 100)
            }
        } else if (!unreadable) {
            unreadableSince = null
            if (bypassOn) turnOff(announce = true)
        }
    }

    private fun isGame(pkg: String): Boolean = runCatching {
        packageManager.getApplicationInfo(pkg, 0).category == android.content.pm.ApplicationInfo.CATEGORY_GAME
    }.getOrDefault(false)

    private val appPrefs by lazy { getSharedPreferences("apps", MODE_PRIVATE) }
    private fun appList(name: String): Set<String> = appPrefs.getStringSet(name, emptySet()) ?: emptySet()
    private fun addToList(name: String, pkg: String) {
        val set = appList(name)
        if (pkg !in set) appPrefs.edit().putStringSet(name, set + pkg).apply()
    }

    /** How many things with words TalkBack can find (stops counting at 6). */
    private fun countReadable(root: AccessibilityNodeInfo): Int {
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
                    if (readable >= 6) return readable
                }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { stack.add(it) }
        }
        return readable
    }

    /** "Unreadable" means TalkBack would find at most one thing with words on it. */
    private fun isUnreadable(root: AccessibilityNodeInfo): Boolean = countReadable(root) <= 1

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
        // A thin line at the very bottom edge, so it doesn't cover the game.
        minHeight = 0
        minimumHeight = 0
        minWidth = 0
        minimumWidth = 0
        setPadding(0, 0, 0, 0)
        textSize = 1f
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, stripHeight(), 1f)
    }

    private fun stripHeight() = (10 * resources.displayMetrics.density).toInt()

    private fun showControlBar() {
        if (controlBar != null) return
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        val status = TextView(this).apply {
            setTextColor(Color.TRANSPARENT)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            isFocusable = true
            textSize = 1f
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, stripHeight(), 1f)
        }
        bar.addView(status)
        bar.addView(invisibleButton("Describe") { describeFocused() })
        bar.addView(invisibleButton("Rescan") {
            currentTexts = emptySet()
            emptyScans = 0
            rescanRequested = true
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
        bar.addView(invisibleButton("Never use Game Bypass in this app") {
            currentPackage?.let { pkg ->
                addToList("never", pkg)
                val always = appList("always")
                if (pkg in always) appPrefs.edit().putStringSet("always", always - pkg).apply()
            }
            turnOff(announce = false)
            setStatus("Game Bypass won't turn on in this app anymore. You can change that in the Game Bypass app.")
            handler.postDelayed(removeBarRunnable, 3000)
        })
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
        if (!bypassOn || scanning || tapping || imeShowing) return
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
                    val boxes = runCatching { findEmptyBoxes(bmp) }.getOrDefault(emptyList())
                    recognizer.process(InputImage.fromBitmap(bmp, 0))
                        .addOnSuccessListener { first ->
                            recognizer.process(InputImage.fromBitmap(contrast, 0))
                                .addOnSuccessListener { second ->
                                    scanning = false
                                    keepShot(bmp); contrast.recycle()
                                    onTextFound(listOf(first, second), boxes)
                                }
                                .addOnFailureListener { e ->
                                    scanning = false
                                    keepShot(bmp); contrast.recycle()
                                    Log.e(tag, "Second OCR pass failed", e)
                                    onTextFound(listOf(first), boxes)
                                }
                        }
                        .addOnFailureListener { e ->
                            scanning = false
                            keepShot(bmp); contrast.recycle()
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

    private fun onTextFound(results: List<Text>, boxes: List<Rect> = emptyList()) {
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
        val baseItems = orderForReading(mergeNearbyLines(lines))
            .map { if (looksLikeTextBox(it.text)) it.copy(text = "${it.text}, text box") else it }
        // Text boxes: real Android ones, and empty boxes in the picture. Not during a Hearthstone match.
        val inMatch = profile == "hearthstone" &&
            HearthstoneBoard.isMatch(baseItems.map { HearthstoneBoard.Word(it.text, it.rect) })
        val items = if (inMatch) baseItems else orderForReading(baseItems + textBoxItems(baseItems, boxes))
        val texts = items.map { it.text.lowercase() }.toSet()

        latestItems = items
        val announceCount = rescanRequested
        rescanRequested = false
        fun found(n: Int) = if (n == 0) "Found no buttons." else "Found $n button${if (n == 1) "" else "s"}."
        if (items.isEmpty()) {
            if (announceCount) setStatus(found(0))
            emptyScans++
            // Hearthstone never needs automatic direct touch.
            if (emptyScans == 3 && !directTouch && profile != "hearthstone") {
                setDirectTouch(true, auto = true, speak = true)
            }
            return
        }
        emptyScans = 0

        if (profile == "hearthstone" && !directTouch && handleHearthstone(items)) {
            if (announceCount) setStatus(found(hsEntries.count { it.kind != K.HEADING }))
            return
        }

        if (directTouch) {
            // A menu with words showed up again: go back to reading mode, but only
            // if direct touch was switched on automatically.
            if (directTouchAuto && items.size >= 3) {
                setDirectTouch(false, auto = false, speak = true)
            } else {
                speakNewGameText(items)
            }
            if (announceCount) setStatus(found(items.size))
            return
        }

        if (!announceCount && similarity(texts, currentTexts) >= 0.7) return // same screen, keep TalkBack's place
        val firstTime = currentTexts.isEmpty()
        currentTexts = texts
        buildItems(items)
        autoLabel(items)
        val count = "${items.size} button${if (items.size == 1) "" else "s"}"
        setStatus(
            when {
                announceCount -> found(items.size)
                firstTime -> "Game Bypass Mode. $count."
                else -> "Screen changed. $count."
            }
        )
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

    // ------------------------------------------------------ text boxes

    private val fieldWords = Regex(
        "^(enter|type|search|e-?mail|password|user ?name|username|verification|security code|" +
            "code|battle ?tag|account|phone|first name|last name|name)\\b|enter (the |your )?code|email address",
        RegexOption.IGNORE_CASE
    )

    private fun looksLikeTextBox(text: String) =
        text.length <= 40 && !text.contains("text box") && fieldWords.containsMatchIn(text.trim())

    /** Double tap on an item. Real Android text boxes get focused directly; everything else is tapped. */
    private fun activateItem(item: Item) {
        val node = item.node
        if (node != null) {
            val now = System.currentTimeMillis()
            if (now - lastTapAt < 400) return
            lastTapAt = now
            val ok = runCatching {
                node.refresh()
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK) ||
                    node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            }.getOrDefault(false)
            if (!ok) tapAt(item.rect)
            return
        }
        tapAt(item.rect)
    }

    /**
     * Extra items for text boxes:
     *  - real Android text boxes the game shows (like Blizzard's web login pages),
     *  - empty rectangles in the picture, named after the closest words above or to the left.
     */
    private fun textBoxItems(items: List<Item>, boxes: List<Rect>): List<Item> {
        val out = mutableListOf<Item>()
        // Real Android text boxes.
        frontAppRoot()?.let { root ->
            val stack = ArrayDeque<AccessibilityNodeInfo>()
            stack.add(root)
            var visited = 0
            while (stack.isNotEmpty() && visited < 400) {
                val n = stack.removeLast()
                visited++
                if (n.isEditable && n.isVisibleToUser) {
                    val r = Rect().also { n.getBoundsInScreen(it) }
                    val label = n.hintText?.toString()?.takeIf { it.isNotBlank() }
                        ?: n.contentDescription?.toString()?.takeIf { it.isNotBlank() }
                        ?: n.text?.toString()?.takeIf { it.isNotBlank() && !n.isPassword }
                    out += Item(if (label != null) "Text box, $label" else "Text box", r, n)
                }
                for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
            }
        }
        // Empty boxes from the picture.
        val screen = wm.currentWindowMetrics.bounds
        for (raw in boxes.take(6)) {
            val b = Rect((raw.left * scaleX).toInt(), (raw.top * scaleY).toInt(), (raw.right * scaleX).toInt(), (raw.bottom * scaleY).toInt())
            if (items.any { b.contains(it.rect.centerX(), it.rect.centerY()) }) continue // has words inside: a button, not a text box
            if (out.any { overlapRatio(it.rect, b) > 0.5f }) continue
            if (controlBarRect()?.let { Rect.intersects(it, b) } == true) continue
            val above = items.filter {
                it.rect.bottom <= b.top + b.height() / 3 && b.top - it.rect.bottom < b.height() * 1.5f &&
                    min(it.rect.right, b.right) - max(it.rect.left, b.left) > 0
            }.maxByOrNull { it.rect.bottom }
            val left = items.filter {
                it.rect.right <= b.left + 4 && b.left - it.rect.right < screen.width() * 0.3f &&
                    kotlin.math.abs(it.rect.centerY() - b.centerY()) < b.height()
            }.maxByOrNull { it.rect.right }
            val label = (above ?: left)?.text?.removeSuffix(", text box")
            out += Item(if (label != null) "Text box, $label" else "Text box", b)
        }
        return out
    }

    /**
     * Looks for empty, flat-colored rectangles that are wider than they are tall,
     * which is what text boxes usually look like. Works on a small copy of the picture.
     */
    private fun findEmptyBoxes(src: Bitmap): List<Rect> {
        val sw = 240
        val sh = (src.height * sw.toFloat() / src.width).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(src, sw, sh, true)
        val px = IntArray(sw * sh)
        small.getPixels(px, 0, sw, 0, 0, sw, sh)
        small.recycle()
        val g = IntArray(sw * sh) { i ->
            val c = px[i]
            (((c shr 16) and 255) * 3 + ((c shr 8) and 255) * 6 + (c and 255)) / 10
        }
        // Horizontal edges (a strong change from the row above to the row below).
        data class Seg(val y: Int, val x0: Int, val x1: Int)
        val segs = mutableListOf<Seg>()
        val minLen = (sw * 0.15f).toInt()
        for (y in 1 until sh - 1) {
            var start = -1
            var gap = 0
            for (x in 0 until sw) {
                val edge = kotlin.math.abs(g[(y + 1) * sw + x] - g[(y - 1) * sw + x]) > 40
                if (edge) {
                    if (start < 0) start = x
                    gap = 0
                } else if (start >= 0) {
                    gap++
                    if (gap > 2) {
                        val end = x - gap
                        if (end - start >= minLen) segs += Seg(y, start, end)
                        start = -1; gap = 0
                    }
                }
            }
            if (start >= 0 && sw - 1 - start >= minLen) segs += Seg(y, start, sw - 1)
        }
        // Keep one line per thick edge.
        val lines = mutableListOf<Seg>()
        for (sg in segs) {
            if (lines.none { kotlin.math.abs(it.y - sg.y) <= 2 && kotlin.math.abs(it.x0 - sg.x0) <= 3 && kotlin.math.abs(it.x1 - sg.x1) <= 3 }) lines += sg
        }
        val out = mutableListOf<Rect>()
        val minH = (sh * 0.03f).toInt().coerceAtLeast(3)
        val maxH = (sh * 0.12f).toInt()
        for (top in lines) for (bot in lines) {
            val hgt = bot.y - top.y
            if (hgt < minH || hgt > maxH) continue
            if (kotlin.math.abs(top.x0 - bot.x0) > 3 || kotlin.math.abs(top.x1 - bot.x1) > 3) continue
            val x0 = max(top.x0, bot.x0)
            val x1 = min(top.x1, bot.x1)
            val wdt = x1 - x0
            if (wdt < hgt * 2.5f || wdt > hgt * 14f) continue
            // Inside must be flat (no words, no picture).
            var sum = 0L; var sq = 0L; var n = 0
            for (y in top.y + 2 until bot.y - 1) for (x in x0 + 2 until x1 - 1) {
                val v = g[y * sw + x]; sum += v; sq += v.toLong() * v; n++
            }
            if (n < 20) continue
            val mean = sum.toDouble() / n
            val variance = sq.toDouble() / n - mean * mean
            if (variance > 144) continue
            val r = Rect(
                (x0 * src.width / sw), (top.y * src.height / sh),
                (x1 * src.width / sw), (bot.y * src.height / sh)
            )
            if (out.none { overlapRatio(it, r) > 0.5f }) out += r
            if (out.size >= 6) return out
        }
        return out
    }

    /** The keyboard opened or closed. Get out of its way so you can type. */
    private fun onKeyboardChanged(open: Boolean) {
        val bar = controlBar
        if (open) {
            setItemsTouchable(false)
            bar?.let { b ->
                b.visibility = View.INVISIBLE
                (b.layoutParams as? WindowManager.LayoutParams)?.let { lp ->
                    lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    runCatching { wm.updateViewLayout(b, lp) }
                }
            }
            speakGame("Keyboard open.")
        } else {
            if (!directTouch) setItemsTouchable(true)
            bar?.let { b ->
                b.visibility = View.VISIBLE
                (b.layoutParams as? WindowManager.LayoutParams)?.let { lp ->
                    lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                    runCatching { wm.updateViewLayout(b, lp) }
                }
            }
            speakGame("Keyboard closed.")
            currentTexts = emptySet()
            handler.postDelayed({ scan() }, 500)
        }
    }

    // ------------------------------------------------------ Smart labels

    private fun keepShot(bmp: Bitmap) {
        val old = lastShot
        lastShot = bmp
        if (old != null && old !== bmp) old.recycle()
    }

    private fun labelKey(item: Item) =
        "${currentPackage}|${item.text.lowercase()}|${item.rect.centerX() / 60}|${item.rect.centerY() / 60}"

    private fun labelFor(item: Item): String {
        val saved = labelPrefs.getString(labelKey(item), null)
        return if (saved != null) "${item.text}, $saved" else item.text
    }

    /** Buttons that need a picture description: just numbers, or a few odd letters. */
    private fun isWeird(text: String): Boolean {
        val t = text.removeSuffix(", text box").trim()
        if (t.matches(Regex("^[\\d\\s,./%+x:-]+$"))) return true
        val letters = t.filter { it.isLetter() }
        if (letters.length in 1..3 && !t.contains(' ')) return true
        return letters.length >= 4 && letters.none { it.lowercaseChar() in "aeiouy" }
    }

    /** Queues up to 4 weird buttons on this screen for the on-phone AI. */
    private fun autoLabel(items: List<Item>) {
        labelQueue.clear()
        items.filter { isWeird(it.text) && !labelPrefs.contains(labelKey(it)) }
            .take(4)
            .forEach { labelQueue.addLast(it) }
        runLabelQueue()
    }

    private fun runLabelQueue() {
        if (describing || labelQueue.isEmpty()) return
        labeler.prepare { ok ->
            if (!ok) {
                labelQueue.clear()
                if (!labelerWarned) {
                    labelerWarned = true
                    speakGame("Smart labels are off: ${labeler.problem ?: "the phone's offline AI isn't available"}.")
                }
                return@prepare
            }
            val item = labelQueue.removeFirstOrNull() ?: return@prepare
            val crop = cropAround(item.rect, wide = true) ?: return@prepare
            describing = true
            labeler.describe(crop) { text, problem ->
                describing = false
                crop.recycle()
                if (text != null) {
                    val label = "picture: " + shorten(text)
                    labelPrefs.edit().putString(labelKey(item), label).apply()
                    itemViews.firstOrNull { it.first == item }?.second?.contentDescription = "${item.text}, $label"
                } else {
                    Log.w(tag, "Describe failed: $problem")
                }
                runLabelQueue()
            }
        }
    }

    /** Cuts out the area around a button from the last screenshot (with the picture next to it). */
    private fun cropAround(screenRect: Rect, wide: Boolean): Bitmap? {
        val shot = lastShot ?: return null
        if (shot.isRecycled) return null
        val r = Rect(
            (screenRect.left / scaleX).toInt(), (screenRect.top / scaleY).toInt(),
            (screenRect.right / scaleX).toInt(), (screenRect.bottom / scaleY).toInt()
        )
        if (wide) {
            val padX = max(r.width(), r.height() * 2)
            val padY = r.height()
            r.inset(-padX, -padY)
        }
        if (!r.intersect(0, 0, shot.width, shot.height) || r.width() < 8 || r.height() < 8) return null
        return Bitmap.createBitmap(shot, r.left, r.top, r.width(), r.height())
    }

    private fun shorten(description: String): String {
        val first = description.trim().split(Regex("(?<=[.!?])\\s")).first().trim().removeSuffix(".")
        val short = if (first.length > 90) first.take(90).substringBeforeLast(' ') else first
        return short.replaceFirstChar { it.lowercase() }
    }

    /** The Describe button: describes the button you were on, or the whole screen. */
    private fun describeFocused() {
        val item = focusedItem
        val picture = if (item != null) cropAround(item.rect, wide = true) else lastShot?.let {
            if (it.isRecycled) null else it.copy(Bitmap.Config.ARGB_8888, false)
        }
        if (picture == null) {
            setStatus("Nothing to describe yet. Try Rescan first.")
            return
        }
        setStatus(if (item != null) "Describing ${item.text}." else "Describing the screen.")
        labeler.prepare { ok ->
            if (!ok) {
                picture.recycle()
                setStatus("Can't describe: ${labeler.problem ?: "the phone's offline AI isn't available"}.")
                return@prepare
            }
            labeler.describe(picture) { text, problem ->
                picture.recycle()
                if (text == null) {
                    setStatus("Couldn't describe it. ${problem ?: ""}")
                    return@describe
                }
                setStatus(text)
                if (item != null) {
                    val label = "picture: " + shorten(text)
                    labelPrefs.edit().putString(labelKey(item), label).apply()
                    itemViews.firstOrNull { it.first == item }?.second?.contentDescription = "${item.text}, $label"
                }
            }
        }
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
        itemViews.clear()
        focusedRect = null
        val screen = wm.currentWindowMetrics.bounds
        // Make each button's touch area bigger (toward its neighbors), then fill
        // big empty parts of the screen, so sliding your finger anywhere says something.
        val areas = growAreas(items.map { it.rect }, screen)
        // Empty areas go in first, so real buttons sit on top of them.
        val all = emptyAreas(areas, screen).map { it to it.rect } + items.zip(areas)
        for ((item, area) in all) {
            val r = item.rect
            val view = View(this).apply {
                contentDescription = labelFor(item)
                isFocusable = true
                isClickable = true
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                // Way 1: a normal click on the item.
                setOnClickListener { activateItem(item) }
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
                                focusedItem = item
                            }
                            // Way 2: TalkBack's double tap arrives as a "click" action. Tap the game.
                            AccessibilityNodeInfo.ACTION_CLICK -> {
                                activateItem(item)
                                return true
                            }
                        }
                        return super.performAccessibilityAction(host, action, args)
                    }
                }
            }
            val lp = FrameLayout.LayoutParams(area.width(), area.height()).apply {
                leftMargin = area.left - itemsOffset[0]
                topMargin = area.top - itemsOffset[1]
            }
            layer.addView(view, lp)
            itemViews += item to view
        }
    }

    /**
     * Grows each button's touch area: at least fingertip size, then outward toward
     * its neighbors (halfway to the next button), up to a limit.
     */
    private fun growAreas(rects: List<Rect>, screen: Rect): List<Rect> {
        val dp = resources.displayMetrics.density
        val minSize = (48 * dp).toInt()
        val capX = (screen.width() * 0.10f).toInt()
        val capY = (screen.height() * 0.08f).toInt()
        return rects.mapIndexed { i, r0 ->
            val r = Rect(r0)
            if (r.width() < minSize) r.inset(-(minSize - r.width()) / 2, 0)
            if (r.height() < minSize) r.inset(0, -(minSize - r.height()) / 2)
            val others = rects.filterIndexed { j, _ -> j != i }
            fun rowOverlap(o: Rect) = min(o.bottom, r.bottom) - max(o.top, r.top) > 0
            fun colOverlap(o: Rect) = min(o.right, r.right) - max(o.left, r.left) > 0
            val leftGap = others.filter { rowOverlap(it) && it.right <= r0.left }.minOfOrNull { r0.left - it.right } ?: Int.MAX_VALUE
            val rightGap = others.filter { rowOverlap(it) && it.left >= r0.right }.minOfOrNull { it.left - r0.right } ?: Int.MAX_VALUE
            val upGap = others.filter { colOverlap(it) && it.bottom <= r0.top }.minOfOrNull { r0.top - it.bottom } ?: Int.MAX_VALUE
            val downGap = others.filter { colOverlap(it) && it.top >= r0.bottom }.minOfOrNull { it.top - r0.bottom } ?: Int.MAX_VALUE
            Rect(
                min(r.left, r0.left - min(leftGap / 2, capX)),
                min(r.top, r0.top - min(upGap / 2, capY)),
                max(r.right, r0.right + min(rightGap / 2, capX)),
                max(r.bottom, r0.bottom + min(downGap / 2, capY))
            ).apply { intersect(screen) }
        }
    }

    /**
     * Splits the screen into 9 parts (top left, top, top right, ... bottom right).
     * Any part with no buttons gets an "Empty area" item, so it isn't silent.
     * Double tap taps the game there; Describe tells you what that part looks like.
     */
    private fun emptyAreas(areas: List<Rect>, screen: Rect): List<Item> {
        val names = listOf(
            "top left", "top", "top right",
            "left", "middle", "right",
            "bottom left", "bottom", "bottom right"
        )
        val cw = screen.width() / 3
        val ch = screen.height() / 3
        val out = mutableListOf<Item>()
        for (row in 0 until 3) for (col in 0 until 3) {
            val cell = Rect(col * cw, row * ch, (col + 1) * cw, (row + 1) * ch)
            val covered = areas.sumOf { a ->
                val i = Rect()
                if (i.setIntersect(a, cell)) i.width().toLong() * i.height() else 0L
            }
            // Mostly empty (less than a quarter covered by buttons).
            if (covered * 4 < cell.width().toLong() * cell.height()) {
                out += Item("Empty area, ${names[row * 3 + col]}", cell)
            }
        }
        return out
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
                if (directTouch || imeShowing) params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
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
