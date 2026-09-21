package com.jev.probe.core

/** One captured chat bubble. WeChat side: name-above=other, then avatar, then green, then X. */
data class Msg(
    val side: String,
    val text: String,
    val speaker: String? = null
)

/** A snapshot of the currently-open conversation in whichever chat app is
 *  foreground (see ChatAppAdapter). */
/** Screen box of one bubble, used to sample color when the user marks "this is me". */
data class BubbleBound(val left: Int, val top: Int, val right: Int, val bottom: Int)

data class ChatSnapshot(
    val title: String?,
    val messages: List<Msg>,
    val kind: ChatKind = ChatKind.DM,
    val mentionedMe: Boolean = false,
    val memberCount: Int? = null,
    val group: GroupContext? = null,
    val lastBound: BubbleBound? = null,
    val lastVisibleText: String? = null,
    val skipReason: String? = null
) {
    val latestFrom: String? get() = messages.lastOrNull()?.side
    val latestSpeaker: String? get() = messages.lastOrNull { it.side == "other" }?.speaker

    /** A stable signature of the last few messages, to detect real changes. */
    fun signature(): String =
        messages.takeLast(6).joinToString("|") { "${it.side}:${it.speaker ?: ""}:${it.text}" } +
            "|${kind.name}|@=$mentionedMe|${group?.digest ?: ""}"
}

/** Jev's judgment result for one snapshot, plus the ranked candidate replies. */
data class Analysis(
    val trueIntent: Choice?,
    val dangerLevel: Score?,
    val sheNeeds: Choice?,
    val shouldReplyNow: Double?,
    val bestAction: Choice?,
    val tensionResolved: Double?,
    val literalQuestion: Double?,
    val rankedReplies: List<RankedReply>,
    val latencyMs: Long,
    val error: String? = null,
    val addressedToMe: Double? = null,
    val groupRegister: Choice? = null,
    val openLoop: Double? = null,
    val replyTarget: Choice? = null,
    val threadStatus: Choice? = null
)

data class Choice(val choice: String, val confidence: Double, val probabilities: Map<String, Double>)
data class Score(val score: Double, val confidence: Double, val maxLevel: Int)
data class RankedReply(val text: String, val prob: Double)
