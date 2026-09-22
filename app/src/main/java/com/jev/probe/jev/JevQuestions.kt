package com.jev.probe.jev

import android.content.Context
import android.util.Log
import com.jev.probe.core.ChatKind
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.MoodHint
import com.jev.probe.core.kb.ChatContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Fixed Jev question set, loaded from assets/jev_questions.json (same file
 * tools/jev/questions.py reads). Instructions/criteria in English; chat text
 * stays Chinese.
 */
object JevQuestions {

    private const val ASSET = "jev_questions.json"
    private const val DEFAULT_RANK =
        "Which candidate reply is the most appropriate next message, " +
            "given the conversation and the other person's true need? " +
            "Prefer a reply that matches the best action type. " +
            "Penalize dismissive, over-promising, or off-topic replies. " +
            "If the facts are not yet confirmed, prefer the candidate that looks them up " +
            "instead of faking memory or a vague apology."
    private const val DEFAULT_RANK_GROUP =
        "Which candidate is the most appropriate NEXT message in this GROUP chat? " +
            "Prefer a reply that matches best_action. Penalize couple-chat tone, " +
            "long apologies, @everyone, answering when wait was correct, " +
            "and inventing facts not in the snippet."

    @Volatile private var judgeJson: JSONObject? = null
    @Volatile private var groupJson: JSONObject? = null
    @Volatile private var rankInstructions: String = DEFAULT_RANK
    @Volatile private var rankGroupInstructions: String = DEFAULT_RANK_GROUP

    fun init(ctx: Context) {
        try {
            val raw = ctx.assets.open(ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
            val root = JSONObject(raw)
            judgeJson = root.getJSONObject("judge")
            groupJson = root.optJSONObject("judge_group")
            val rank = root.optJSONObject("rank")
            val inst = rank?.optString("instructions").orEmpty()
            if (inst.isNotBlank()) rankInstructions = inst
            val gInst = rank?.optString("group_instructions").orEmpty()
            if (gInst.isNotBlank()) rankGroupInstructions = gInst
        } catch (e: Exception) {
            Log.e("JEVASSIST", "failed to load $ASSET: ${e.message}")
        }
    }

    fun judge(snapshot: ChatSnapshot): JSONObject {
        val src = if (snapshot.kind == ChatKind.GROUP) groupJson ?: judgeJson else judgeJson
        return JSONObject((src ?: error("JevQuestions not initialized")).toString())
    }

    fun buildState(
        snapshot: ChatSnapshot,
        relationship: String,
        mood: MoodHint? = null,
        ctx: ChatContext? = null
    ): JSONObject {
        val msgs = JSONArray()
        val last10 = snapshot.messages.takeLast(10)
        for (m in last10) {
            val o = JSONObject().put("from", m.side).put("text", m.text)
            if (!m.speaker.isNullOrBlank()) o.put("speaker", m.speaker)
            msgs.put(o)
        }
        val chat = JSONObject()
            .put("relationship", relationship)
            .put("kind", if (snapshot.kind == ChatKind.GROUP) "group" else "direct")
            .put("mentioned_me", snapshot.mentionedMe)
            .put("messages", msgs)
            .put("latest_from", last10.lastOrNull()?.side ?: "other")
        snapshot.title?.let { chat.put("title", it) }
        snapshot.memberCount?.let { chat.put("member_count", it) }
        snapshot.latestSpeaker?.let { chat.put("latest_speaker", it) }
        snapshot.group?.let { g ->
            chat.put("mentioned_earlier", g.mentionedEarlier)
            chat.put("directed_at_me", g.directedAtMe)
            if (g.speakers.isNotEmpty()) chat.put("speakers", JSONArray(g.speakers))
            if (g.atTargets.isNotEmpty()) chat.put("at_targets", JSONArray(g.atTargets))
            if (g.watchHits.isNotEmpty()) chat.put("watch_hits", JSONArray(g.watchHits))
            g.openAsk?.let { chat.put("open_ask", it) }
            g.openAskSpeaker?.let { chat.put("open_ask_speaker", it) }
        }
        mood?.priorAffect?.let {
            chat.put("prior_affect", it)
            chat.put("prior_tension_open", true)
        }
        mood?.judgedAffect?.let { chat.put("judged_affect", it) }
        mood?.sheNeeds?.let { chat.put("she_needs", it) }
        mood?.bestAction?.let { chat.put("best_action", it) }
        mood?.danger?.let { chat.put("danger_level", it) }
        ctx?.background(relationship)?.takeIf { it.isNotBlank() }?.let { chat.put("background", it) }
        if (ctx != null && ctx.history.isNotEmpty()) {
            val hist = JSONArray()
            ctx.history.takeLast(12).forEach { e ->
                hist.put(JSONObject().put("from", e.side).put("text", e.text))
            }
            chat.put("history", hist)
        }
        return JSONObject().put("chat", chat)
    }

    fun rankQuestion(candidates: List<String>, snapshot: ChatSnapshot? = null): JSONObject {
        require(candidates.size == 3) { "rankQuestion expects exactly 3 candidates" }
        val keys = listOf("reply_a", "reply_b", "reply_c")
        val criteria = JSONObject()
        keys.forEachIndexed { i, k -> criteria.put(k, candidates[i]) }
        val inst = if (snapshot?.kind == ChatKind.GROUP) rankGroupInstructions else rankInstructions
        val q = JSONObject().apply {
            put("type", "choice")
            put("instructions", inst)
            put("criteria", criteria)
        }
        return JSONObject().put("best_reply", q)
    }
}
