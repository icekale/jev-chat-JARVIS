package com.jev.probe.core

import org.json.JSONArray
import org.json.JSONObject

/** Per-chat relationship overrides. Blank text clears the override. */
object ChatRel {
    const val MAX = 32
    const val MAX_LEN = 120

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
