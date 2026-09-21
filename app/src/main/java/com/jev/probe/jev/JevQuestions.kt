package com.jev.probe.jev

import android.content.Context
import android.util.Log
import com.jev.probe.core.ChatSnapshot
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

    @Volatile private var judgeJson: JSONObject? = null
    @Volatile private var rankInstructions: String = DEFAULT_RANK

    fun init(ctx: Context) {
        try {
            val raw = ctx.assets.open(ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
            val root = JSONObject(raw)
            judgeJson = root.getJSONObject("judge")
            val rank = root.optJSONObject("rank")
            val inst = rank?.optString("instructions").orEmpty()
            if (inst.isNotBlank()) rankInstructions = inst
        } catch (e: Exception) {
            Log.e("JEVASSIST", "failed to load $ASSET: ${e.message}")
        }
    }

    /** The 7 judgment questions. Returns a fresh JSONObject each call. */
    fun judge(): JSONObject {
        val src = judgeJson ?: error("JevQuestions not initialized")
        return JSONObject(src.toString())
    }

    /** Build Jev state from a snapshot (last 10 messages). */
    fun buildState(snapshot: ChatSnapshot, relationship: String): JSONObject {
        val msgs = JSONArray()
        val last10 = snapshot.messages.takeLast(10)
        for (m in last10) {
            msgs.put(JSONObject().put("from", m.side).put("text", m.text))
        }
        val chat = JSONObject()
            .put("relationship", relationship)
            .put("messages", msgs)
            .put("latest_from", last10.lastOrNull()?.side ?: "other")
        return JSONObject().put("chat", chat)
    }

    /** The best_reply ranking question over exactly 3 candidates (Chinese text kept). */
    fun rankQuestion(candidates: List<String>): JSONObject {
        require(candidates.size == 3) { "rankQuestion expects exactly 3 candidates" }
        val keys = listOf("reply_a", "reply_b", "reply_c")
        val criteria = JSONObject()
        keys.forEachIndexed { i, k -> criteria.put(k, candidates[i]) }
        val q = JSONObject().apply {
            put("type", "choice")
            put("instructions", rankInstructions)
            put("criteria", criteria)
        }
        return JSONObject().put("best_reply", q)
    }
}
