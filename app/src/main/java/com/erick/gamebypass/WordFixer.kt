package com.erick.gamebypass

import android.content.Context
import kotlin.concurrent.thread

/**
 * Built-in dictionary cleanup (free, offline, no AI).
 *
 *  - Fixes misread words that are one letter off a real word: "Spln" becomes "Spin".
 *  - Fixes digits read instead of letters: "C0llect" becomes "Collect".
 *  - Removes stray single letters: "Play l" becomes "Play".
 *  - Throws out short nonsense like "lj". Longer unknown words (game names) are kept.
 *
 * The word list is made during the GitHub build, most common words first.
 */
object WordFixer {
    @Volatile
    private var rank: HashMap<String, Int>? = null

    fun load(context: Context) {
        if (rank != null) return
        thread(name = "word-list") {
            runCatching {
                val map = HashMap<String, Int>(70000)
                context.assets.open("words.txt").bufferedReader().useLines { lines ->
                    lines.forEachIndexed { i, w -> if (w.isNotBlank()) map.putIfAbsent(w.trim(), i) }
                }
                rank = map
            }
        }
    }

    private fun isWord(w: String) = rank?.containsKey(w.lowercase()) == true

    /** Returns the cleaned text, or null if it's junk. Unchanged if the list isn't loaded. */
    fun fix(text: String): String? {
        val dict = rank ?: return text
        val tokens = text.split(' ').filter { it.isNotEmpty() }
        val fixed = mutableListOf<String>()
        for (token in tokens) {
            val lead = token.takeWhile { !it.isLetterOrDigit() }
            val trail = token.takeLastWhile { !it.isLetterOrDigit() }
            if (lead.length + trail.length >= token.length) { fixed += token; continue }
            val core = token.substring(lead.length, token.length - trail.length)
            val letters = core.count { it.isLetter() }
            val digits = core.count { it.isDigit() }

            val result = when {
                // A stray single letter in the middle of other words.
                letters == 1 && digits == 0 && tokens.size > 1 &&
                    core.lowercase() !in setOf("a", "i") -> null
                // Digits read instead of letters, like "C0llect".
                letters >= 2 && digits in 1..2 && core.all { it.isLetterOrDigit() } ->
                    digitFix(core) ?: core
                // A word that's one letter off a real word.
                digits == 0 && letters >= 4 && core.all { it.isLetter() } && !isWord(core) ->
                    closest(core, dict)?.let { keepCase(core, it) } ?: core
                else -> core
            }
            if (result != null) fixed += lead + result + trail
        }
        val out = fixed.joinToString(" ").trim()
        if (out.isEmpty()) return null

        // Short nonsense with no real words, numbers or capitals-only abbreviations: junk.
        val allLetters = out.filter { it.isLetter() }
        val hasDigit = out.any { it.isDigit() }
        val anyWord = out.split(' ').any { isWord(it.filter { c -> c.isLetter() }) }
        if (!hasDigit && allLetters.length in 1..4 && !anyWord && !allLetters.all { it.isUpperCase() }) return null
        return out
    }

    private val swaps = mapOf('0' to listOf('o'), '1' to listOf('l', 'i'), '5' to listOf('s'), '8' to listOf('b'))

    private fun digitFix(core: String): String? {
        var options = listOf("")
        for (c in core) {
            val choices = if (c.isDigit()) swaps[c] ?: return null else listOf(c)
            options = options.flatMap { p -> choices.map { p + it } }
            if (options.size > 8) return null
        }
        return options.firstOrNull { isWord(it) }?.let { keepCase(core, it) }
    }

    /** The most common real word one letter away (one wrong, missing, extra or swapped letter). */
    private fun closest(word: String, dict: HashMap<String, Int>): String? {
        val w = word.lowercase()
        val abc = "abcdefghijklmnopqrstuvwxyz"
        var best: String? = null
        var bestRank = Int.MAX_VALUE
        fun check(c: String) {
            val r = dict[c] ?: return
            if (r < bestRank) { best = c; bestRank = r }
        }
        for (i in w.indices) {
            check(w.removeRange(i, i + 1))
            if (i < w.length - 1) check(w.substring(0, i) + w[i + 1] + w[i] + w.substring(i + 2))
            for (ch in abc) if (ch != w[i]) check(w.substring(0, i) + ch + w.substring(i + 1))
        }
        for (i in 0..w.length) for (ch in abc) check(w.substring(0, i) + ch + w.substring(i))
        return best
    }

    /** Keeps the original capitals: SPLN -> SPIN, Spln -> Spin, spln -> spin. */
    private fun keepCase(original: String, word: String): String = when {
        original.all { !it.isLetter() || it.isUpperCase() } -> word.uppercase()
        original.first().isUpperCase() -> word.replaceFirstChar { it.uppercase() }
        else -> word
    }
}
