package com.jev.probe.core

import kotlin.math.roundToInt

/**
 * What the bubble shows, and when the reply panel may cover the chat.
 * Quiet judgments stay on the bubble. Drafts wait until the panel will open.
 */
object PanelCue {
    enum class Tone { IDLE, SAFE, WATCH, HOT, DANGER }

    /** High danger, or a judgment that substantive content is due. */
    fun shouldAutoOpen(a: Analysis): Boolean {
        val danger = a.dangerLevel?.score?.roundToInt() ?: 0
        if (danger >= 6) return true
        return (a.shouldReplyNow ?: 0.0) >= 0.5
    }

    fun tone(a: Analysis?, priorAffect: String?): Tone {
        if (a == null) return Tone.IDLE
        val felt = Affect.usable(a.affect)?.choice
            ?: priorAffect?.takeIf {
                (a.tensionResolved ?: 0.0) < Affect.COOL_TENSION && it in Affect.HOT
            }
        if (felt != null && felt in Affect.HOT) return Tone.HOT
        val danger = a.dangerLevel?.score?.roundToInt() ?: 0
        return when {
            danger >= 6 -> Tone.DANGER
            danger >= 3 -> Tone.WATCH
            else -> Tone.SAFE
        }
    }

    /** Two-character emotion when it should be obvious; "@" or "!" before a group result. */
    fun bubbleText(mentioned: Boolean, ping: Boolean, a: Analysis?, priorAffect: String?): String {
        if (a == null && mentioned) return "@"
        if (a == null && ping) return "!"
        val choice = a?.let { Affect.usable(it.affect)?.choice }
            ?: priorAffect?.takeIf {
                a != null && (a.tensionResolved ?: 0.0) < Affect.COOL_TENSION && it in Affect.HOT
            }
        if (choice != null && choice in Affect.HOT) return Affect.label(choice)
        return "Jev"
    }

    /** One line above the three replies: emotion · action · whether to give substance. */
    fun summary(a: Analysis, priorAffect: String?, group: Boolean): String {
        val parts = ArrayList<String>()
        val felt = Affect.usable(a.affect)
        val cooled = (a.tensionResolved ?: 0.0) >= Affect.COOL_TENSION
        when {
            felt != null -> parts.add(Affect.label(felt.choice))
            !cooled && priorAffect != null && priorAffect in Affect.LABELS ->
                parts.add(Affect.label(priorAffect))
        }
        val actionMap = if (group) JudgeWords.GROUP_ACTION else JudgeWords.ACTION
        a.bestAction?.choice?.let { actionMap[it] }?.let { parts.add(it) }
        a.shouldReplyNow?.let {
            parts.add(
                if (group) (if (it >= 0.5) "该回" else "先别回")
                else (if (it >= 0.5) "可给实质" else "先别给实质")
            )
        }
        return parts.joinToString(" · ").ifBlank { "已分析" }
    }
}
