package com.jev.probe.core

import org.json.JSONArray
import org.json.JSONObject

/** Per-chat relationship overrides. Blank text clears the override. */
object ChatRel {
    data class RelPreset(val label: String, val text: String)

    const val MAX = 32
    const val MAX_LEN = 120

    val PRESETS = listOf(
        RelPreset("伴侣", "对方是我的伴侣。回得很短，口语，可以不完整，不要客服腔。"),
        RelPreset("朋友", "对方是朋友。松、短、可以开玩笑，不要写小作文。"),
        RelPreset("同事", "对方是同事。就事论事，短，不要情侣语气，不要过度热情。"),
        RelPreset("家人", "对方是家人。像家里发微信，口语，不要书面。"),
        RelPreset("客户", "对方是客户或合作方。礼貌但短，像微信办事，不要公文。")
    )

    fun labelOf(text: String?): String? =
        PRESETS.find { it.text == text?.trim() }?.label

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
