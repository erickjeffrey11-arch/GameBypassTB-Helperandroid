package com.erick.gamebypass

import android.graphics.Rect

/**
 * Hearthstone profile: turns what the OCR read on a match screen into zones,
 * the way Hearthstone Access organizes them on PC:
 *
 *   Your hand, Your minions, Opponent's minions, Heroes, Other.
 *
 * It only looks at where things are on the screen (Hearthstone's match layout
 * is always the same) and the numbers it can read (mana, attack, health).
 * If the screen isn't a match (menus, deck builder, shop), it returns null and
 * the normal Game Bypass reading is used instead.
 */
object HearthstoneBoard {

    enum class Kind { HEADING, HAND_CARD, MY_MINION, ENEMY_MINION, MY_HERO, ENEMY_HERO, HERO_POWER, DROP, CANCEL, OTHER }

    data class Entry(
        val label: String,
        val rect: Rect,
        val kind: Kind,
        /** Stable name for the thing, used to tell "same thing again" from "something else". */
        val key: String,
    )

    data class Word(val text: String, val rect: Rect)

    private val numberRe = Regex("^\\d{1,2}$")
    private val turnRe = Regex("end\\s*turn|enemy\\s*turn|your\\s*turn|opponent'?s?\\s*turn")

    /** Is this screen a match in progress? */
    fun isMatch(words: List<Word>): Boolean =
        words.any { turnRe.containsMatchIn(it.text.lowercase()) }

    fun isEnemyTurn(words: List<Word>): Boolean =
        words.any { Regex("enemy\\s*turn|opponent'?s?\\s*turn").containsMatchIn(it.text.lowercase()) }

    /**
     * Builds the list of swipeable entries, in order, with headings.
     * [selected] is the card or minion you picked with the first double tap, if any.
     */
    fun build(words: List<Word>, w: Int, h: Int, selected: Entry?): List<Entry>? {
        if (!isMatch(words)) return null
        val out = mutableListOf<Entry>()

        val numbers = words.filter { numberRe.matches(it.text.trim()) }
        val texts = words.filter { !numberRe.matches(it.text.trim()) }
        fun ny(r: Rect) = r.centerY().toFloat() / h
        fun nx(r: Rect) = r.centerX().toFloat() / w

        // ---- Bands of the screen (landscape match layout).
        val enemyMinionNums = numbers.filter { ny(it.rect) in 0.27f..0.49f }
        val myMinionNums = numbers.filter { ny(it.rect) in 0.49f..0.70f }
        val handNums = numbers.filter { ny(it.rect) > 0.84f && nx(it.rect) in 0.2f..0.8f }

        val enemyMinions = pairMinions(enemyMinionNums, w, h)
        val myMinions = pairMinions(myMinionNums, w, h)

        // ---- If something is selected, offer a way to cancel first.
        if (selected != null) {
            out += Entry("Cancel, ${selected.label.substringBefore(',')} selected", box(w * 0.5f, h * 0.05f, w, h), Kind.CANCEL, "cancel")
        }

        // ---- Your hand
        out += heading("Your hand", 0.88f, w, h)
        val handCards = handNums.sortedBy { it.rect.centerX() }
        if (handCards.isEmpty()) {
            out += Entry("Hand: no cards could be read", box(w * 0.5f, h * 0.93f, w, h), Kind.OTHER, "hand-empty")
        }
        handCards.forEachIndexed { i, mana ->
            // The card is to the right of and below its mana number.
            val card = Rect(mana.rect.left, mana.rect.top, mana.rect.left + (w * 0.07f).toInt(), h)
            val name = texts.firstOrNull { Rect.intersects(it.rect, card) && it.text.length > 2 }?.text
            val label = "${name ?: "Card ${i + 1}"}, costs ${mana.text}"
            val target = box(card.exactCenterX(), (mana.rect.bottom + h * 0.04f), w, h)
            out += Entry(label, target, Kind.HAND_CARD, "hand-${i + 1}-${name ?: ""}")
        }

        // ---- Your minions, with summon spots when a card from your hand is selected.
        out += heading("Your minions", 0.56f, w, h)
        val myRowY = if (myMinions.isNotEmpty()) myMinions.map { it.centerY() }.average().toFloat() else h * 0.56f
        if (myMinions.isEmpty() && selected?.kind != Kind.HAND_CARD) {
            out += Entry("You have no summoned minions", box(w * 0.5f, myRowY, w, h), Kind.OTHER, "my-empty")
        }
        val sel = selected?.kind
        if (sel == Kind.HAND_CARD && myMinions.isEmpty()) {
            out += Entry("Summon", box(w * 0.5f, myRowY, w, h), Kind.DROP, "drop-center")
        }
        myMinions.forEachIndexed { i, m ->
            val mName = "Your minion ${i + 1}"
            if (sel == Kind.HAND_CARD && i == 0) {
                out += Entry("Summon at the left", box(m.left - w * 0.035f, myRowY, w, h), Kind.DROP, "drop-left")
            }
            out += Entry("$mName, ${m.attack} attack, ${m.health} health", m.rect, Kind.MY_MINION, "my-${i + 1}")
            if (sel == Kind.HAND_CARD) {
                if (i < myMinions.lastIndex) {
                    val next = myMinions[i + 1]
                    out += Entry(
                        "Summon between your minion ${i + 1} and your minion ${i + 2}",
                        box((m.right + next.left) / 2f, myRowY, w, h), Kind.DROP, "drop-${i + 1}-${i + 2}"
                    )
                } else {
                    out += Entry("Summon at the right", box(m.right + w * 0.035f, myRowY, w, h), Kind.DROP, "drop-right")
                }
            }
        }

        // ---- Opponent's minions
        out += heading("Opponent's minions", 0.38f, w, h)
        if (enemyMinions.isEmpty()) {
            out += Entry("Your opponent has no summoned minions", box(w * 0.5f, h * 0.38f, w, h), Kind.OTHER, "enemy-empty")
        }
        enemyMinions.forEachIndexed { i, m ->
            out += Entry("Opponent's minion ${i + 1}, ${m.attack} attack, ${m.health} health", m.rect, Kind.ENEMY_MINION, "enemy-${i + 1}")
        }

        // ---- Heroes and Hero Power (fixed spots on Hearthstone's board)
        out += heading("Heroes", 0.2f, w, h)
        val myHeroArea = boxRect(w * 0.5f, h * 0.78f, w * 0.12f, h * 0.16f)
        val enemyHeroArea = boxRect(w * 0.5f, h * 0.18f, w * 0.12f, h * 0.16f)
        val myHealth = numbers.filter { Rect.intersects(it.rect, myHeroArea) }.maxByOrNull { it.rect.centerX() }?.text
        val enemyHealth = numbers.filter { Rect.intersects(it.rect, enemyHeroArea) }.maxByOrNull { it.rect.centerX() }?.text
        out += Entry("Opponent's Hero" + (enemyHealth?.let { ", $it health" } ?: ""), enemyHeroArea, Kind.ENEMY_HERO, "enemy-hero")
        out += Entry("Your Hero" + (myHealth?.let { ", $it health" } ?: ""), myHeroArea, Kind.MY_HERO, "my-hero")
        out += Entry("Hero Power", box(w * 0.6f, h * 0.78f, w, h), Kind.HERO_POWER, "hero-power")

        // ---- Everything else with words (End Turn, Discover choices, Confirm, mana, etc.)
        // These work with a single double tap, like normal.
        val used = myMinionNums + enemyMinionNums + handNums
        val others = words.filter { word ->
            word !in used &&
                !(Rect.intersects(word.rect, myHeroArea) && numberRe.matches(word.text.trim())) &&
                !(Rect.intersects(word.rect, enemyHeroArea) && numberRe.matches(word.text.trim()))
        }
        if (others.isNotEmpty()) {
            out += heading("Other", 0.46f, w, h)
            others.sortedWith(compareBy({ it.rect.centerY() / (h / 10) }, { it.rect.centerX() })).forEach {
                out += Entry(it.text, it.rect, Kind.OTHER, "other-${it.text.lowercase()}")
            }
        }
        return out
    }

    // ------------------------------------------------------------ helpers

    data class Minion(val attack: String, val health: String, val rect: Rect, val left: Float, val right: Float) {
        fun centerY() = rect.exactCenterY()
    }

    /**
     * A minion on the board shows two numbers side by side under its picture:
     * attack on the left, health on the right. Pair them up from left to right.
     */
    private fun pairMinions(nums: List<Word>, w: Int, h: Int): List<Minion> {
        val sorted = nums.sortedBy { it.rect.centerX() }.toMutableList()
        val out = mutableListOf<Minion>()
        var i = 0
        while (i < sorted.size - 1) {
            val a = sorted[i]
            val b = sorted[i + 1]
            val gap = b.rect.centerX() - a.rect.centerX()
            val sameRow = kotlin.math.abs(a.rect.centerY() - b.rect.centerY()) < h * 0.04f
            if (gap in 1..(w * 0.085f).toInt() && sameRow) {
                val cx = (a.rect.centerX() + b.rect.centerX()) / 2f
                val cy = (a.rect.centerY() + b.rect.centerY()) / 2f - h * 0.05f
                val body = boxRect(cx, cy, w * 0.07f, h * 0.13f)
                out += Minion(a.text.trim(), b.text.trim(), body, body.left.toFloat(), body.right.toFloat())
                i += 2
            } else {
                i += 1
            }
        }
        return out
    }

    private fun heading(label: String, y: Float, w: Int, h: Int) =
        Entry(label, Rect(0, (h * y).toInt(), (w * 0.03f).toInt(), (h * y).toInt() + (h * 0.03f).toInt()), Kind.HEADING, "heading-$label")

    /** A small box (about a fingertip) centered on a point. */
    private fun box(cx: Float, cy: Float, w: Int, h: Int) = boxRect(cx, cy, w * 0.05f, h * 0.08f)

    private fun boxRect(cx: Float, cy: Float, bw: Float, bh: Float) =
        Rect((cx - bw / 2).toInt(), (cy - bh / 2).toInt(), (cx + bw / 2).toInt(), (cy + bh / 2).toInt())
}
