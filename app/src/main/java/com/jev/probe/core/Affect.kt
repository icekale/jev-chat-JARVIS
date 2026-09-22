package com.jev.probe.core

/** One confident read of how the other person feels, kept per chat until tension cools. */
data class ChatMood(val affect: String)

/**
 * Facts from the last judgment that the next Jev call and the draft model may see.
 * [judgedAffect] is set only when this turn's read cleared the confidence bar.
 */
data class MoodHint(
    val priorAffect: String? = null,
    val judgedAffect: String? = null,
    val sheNeeds: String? = null,
    val bestAction: String? = null,
    val danger: Int? = null
)

object Affect {
    /** Below this, hide the label and do not let this turn's emotion steer the draft. */
    const val MIN_CONFIDENCE = 0.55
    const val COOL_TENSION = 0.7

    val LABELS = mapOf(
        "steady" to "平稳",
        "affectionate" to "亲昵",
        "teasing" to "开玩笑",
        "anxious" to "着急",
        "hurt" to "委屈",
        "angry" to "生气",
        "cold" to "冷淡",
        "resigned" to "认命"
    )

    val HOT = setOf("hurt", "angry", "cold", "resigned")

    fun label(choice: String): String = LABELS[choice] ?: choice

    fun usable(c: Choice?): Choice? =
        c?.takeIf { it.choice in LABELS && it.confidence >= MIN_CONFIDENCE }

    /**
     * Keep the last confident feeling across a short follow-up.
     * A clearly cooled thread drops it. A low-confidence new read does not replace it.
     */
    fun remember(prev: ChatMood?, affect: Choice?, tensionResolved: Double?): ChatMood? {
        if (tensionResolved != null && tensionResolved >= COOL_TENSION) return null
        val hit = usable(affect) ?: return prev?.takeIf { it.affect in LABELS }
        return ChatMood(hit.choice)
    }
}
