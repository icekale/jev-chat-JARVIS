package com.jev.probe.core

import org.json.JSONArray
import org.json.JSONObject

/** Per-chat relationship overrides. Blank text clears the override. */
object ChatRel {
    data class RelPreset(
        val label: String,
        val judge: String,
        val voice: String,
        val legacy: String
    )

    const val MAX = 32
    const val MAX_LEN = 120

    const val UNKNOWN_JUDGE =
        "Relationship is not set. Do not assume a romantic partner, a coworker, or a client. from=me is the user and from=other is the other person. A short line is not automatically cold. Judge only from this thread."
    const val UNKNOWN_VOICE =
        "关系还没选。跟着我已经发过的话：一样的长短和口气。不要写成伴侣，也不要写成客服。"
    const val GROUP_JUDGE =
        "This is a group chat. speaker is a member nickname. Decide if the user was addressed before judging intent. Do not treat the latest speaker as a romantic partner. Short lines are normal."
    const val GROUP_VOICE =
        "这是群。一句把事说完。同事就事论事，朋友可以短，家人像家里人。不要情侣腔，不要公文。"

    private const val LEGACY_UNKNOWN =
        "关系还没定。from=me 是我，from=other 是对方。按我在对话里的口气回，不要默认成伴侣或客服。"
    private const val LEGACY_PARTNER =
        "对方是我的伴侣；from=me 的是我发的，from=other 的是对方发的"
    private const val LEGACY_GROUP =
        "这是一个多人群聊。from=me 是我，speaker 是群成员昵称。先判断是不是在叫我，再决定回不回。不要用私聊情侣语气。"

    val PRESETS = listOf(
        RelPreset(
            "伴侣",
            "Romantic partner. from=me is the user. A short or sarcastic line (我习惯了, 你忙你的, 行吧) can be cold or a test of care, not casual chat. A concrete ask can still be a real request. Do not read this as office talk.",
            "对方是伴侣。像私下发微信：很短，可以不完整，可以不带句号。可以接一句、认一句。例：嗯 / 我错了 / 今晚说。不要写一段安慰。",
            "对方是我的伴侣。回得很短，口语，可以不完整，不要客服腔。"
        ),
        RelPreset(
            "朋友",
            "Friend, not a partner. Banter is often just banter. Do not invent a loyalty test or romantic subtext. Coldness is milder than with a partner.",
            "对方是朋友。松、短，可以皮，但一句就够。例：哈哈行 / 你认真的。不要端着，不要写小作文。",
            "对方是朋友。松、短、可以开玩笑，不要写小作文。"
        ),
        RelPreset(
            "同事",
            "Coworker. Deadlines, ownership, and being blamed in front of others matter. A short reply is normal, not coldness. Do not read couple-talk or a test of care into ordinary work talk.",
            "对方是同事。把事说完，不要撒娇。例：我改一版 / 三点前给你。不要「亲」，不要情侣腔。",
            "对方是同事。就事论事，短，不要情侣语气，不要过度热情。"
        ),
        RelPreset(
            "家人",
            "Family. Practical talk and feelings can mix. Do not use workplace formality or romantic framing. A reminder is often just family logistics.",
            "对方是家人。像跟家里人发微信。例：知道了 / 晚上回。不要公文，也不要情侣腔。",
            "对方是家人。像家里发微信，口语，不要书面。"
        ),
        RelPreset(
            "客户",
            "Client or external partner. Commitments and politeness matter. Do not assume intimacy or jokes. Risk is the working relationship, not a breakup.",
            "对方是客户或合作方。礼貌但短，像办事。例：好，我确认下 / 明天给您。不要过度热情，不要叫亲爱的。",
            "对方是客户或合作方。礼貌但短，像微信办事，不要公文。"
        )
    )

    fun presetOf(stored: String?): RelPreset? {
        val t = stored?.trim().orEmpty()
        if (t.isEmpty()) return null
        return PRESETS.find { it.label == t || it.judge == t || it.voice == t || it.legacy == t }
    }

    fun labelOf(text: String?): String? = presetOf(text)?.label

    fun forJudge(override: String?, fallback: String, group: Boolean): String {
        presetOf(override)?.let { return it.judge }
        if (!override.isNullOrBlank()) return override
        presetOf(fallback)?.let { return it.judge }
        if (group && isStockGroup(fallback)) return GROUP_JUDGE
        if (!group && isStockDm(fallback)) return UNKNOWN_JUDGE
        return fallback.ifBlank { if (group) GROUP_JUDGE else UNKNOWN_JUDGE }
    }

    fun forVoice(override: String?, fallback: String, group: Boolean): String {
        presetOf(override)?.let { return it.voice }
        if (!override.isNullOrBlank()) return override
        presetOf(fallback)?.let { return it.voice }
        if (group && isStockGroup(fallback)) return GROUP_VOICE
        if (!group && isStockDm(fallback)) return UNKNOWN_VOICE
        return fallback.ifBlank { if (group) GROUP_VOICE else UNKNOWN_VOICE }
    }

    private fun isStockDm(s: String): Boolean =
        s.isBlank() || s == UNKNOWN_VOICE || s == LEGACY_UNKNOWN || s == LEGACY_PARTNER

    private fun isStockGroup(s: String): Boolean =
        s.isBlank() || s == GROUP_VOICE || s == LEGACY_GROUP

    fun decode(raw: String?): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        if (raw.isNullOrBlank()) return out
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return out
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val k = o.optString("k").trim()
            val v = o.optString("v").trim()
            if (k.isNotEmpty() && v.isNotEmpty()) out[k] = v
        }
        return out
    }

    fun encode(map: Map<String, String>): String {
        val arr = JSONArray()
        for ((k, v) in map) {
            if (k.isBlank() || v.isBlank()) continue
            arr.put(JSONObject().put("k", k).put("v", v))
        }
        return arr.toString()
    }

    fun put(map: Map<String, String>, key: String, text: String): LinkedHashMap<String, String> {
        val next = LinkedHashMap(map)
        val trimmed = text.trim().take(MAX_LEN)
        next.remove(key)
        if (trimmed.isNotEmpty()) next[key] = trimmed
        while (next.size > MAX) {
            val oldest = next.keys.firstOrNull() ?: break
            next.remove(oldest)
        }
        return next
    }
}
