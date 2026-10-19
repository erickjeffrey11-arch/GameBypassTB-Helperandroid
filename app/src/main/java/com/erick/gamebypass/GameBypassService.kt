package com.erick.gamebypass

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
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
        }

        val unreadable = pkg !in ignoredPackages && isUnreadable(root)
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
        showItemsLayer()
        showControlBar()
        setStatus("Game Bypass Mode on. Reading the screen.")
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
                    recognizer.process(InputImage.fromBitmap(bmp, 0))
                        .addOnSuccessListener { text ->
                            scanning = false
                            bmp.recycle()
                            onTextFound(text)
                        }
                        .addOnFailureListener { e ->
                            scanning = false
                            bmp.recycle()
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

    private fun onTextFound(result: Text) {
        if (!bypassOn) return
        val bar = controlBarRect()
        val lines = result.textBlocks.flatMap { it.lines }
            .mapNotNull { line ->
                val box = line.boundingBox ?: return@mapNotNull null
                val words = line.text.trim()
                if (words.isEmpty()) return@mapNotNull null
                if (bar != null && Rect.intersects(bar, box) && box.centerY() > bar.top) return@mapNotNull null
                Item(words, Rect(box))
            }
        val items = orderForReading(mergeNearbyLines(lines))
        val texts = items.map { it.text.lowercase() }.toSet()

        if (items.isEmpty()) {
            emptyScans++
            if (emptyScans == 3 && !directTouch) setDirectTouch(true, auto = true, speak = true)
            return
        }
        emptyScans = 0

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
    private fun speakNewGameText(items: List<Item>) {
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
            setStatus(fresh.take(3).joinToString(". ") { it.text })
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
        val pad = (6 * resources.displayMetrics.density).toInt()
        for (item in items) {
            val r = item.rect
            val view = View(this).apply {
                contentDescription = item.text
                isFocusable = true
                isClickable = true
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                setOnClickListener { tapAt(r) }
            }
            val lp = FrameLayout.LayoutParams(r.width() + pad * 2, r.height() + pad * 2).apply {
                leftMargin = r.left - pad - itemsOffset[0]
                topMargin = r.top - pad - itemsOffset[1]
            }
            layer.addView(view, lp)
        }
    }

    /** Taps the real game at the middle of where the words are. */
    private fun tapAt(r: Rect) {
        tapping = true
        // Our invisible layer would catch the tap itself, so let it fall through for a moment.
        setItemsTouchable(false)
        val path = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        handler.postDelayed({
            val sent = dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) = afterTap()
                override fun onCancelled(g: GestureDescription?) = afterTap()
            }, null)
            if (!sent) afterTap()
        }, 80)
    }

    private fun afterTap() {
        handler.postDelayed({
            tapping = false
            if (!directTouch) setItemsTouchable(true)
            // Look again once the game has reacted.
            handler.postDelayed({ scan() }, 1000)
        }, 150)
    }
}
