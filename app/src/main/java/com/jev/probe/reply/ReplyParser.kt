package com.jev.probe.reply

import org.json.JSONArray
import org.json.JSONObject

object ReplyParser {
    fun extractAssistantText(root: JSONObject): String {
        val choices = root.optJSONArray("choices") ?: return ""
        if (choices.length() == 0) return ""
        val msg = choices.getJSONObject(0).optJSONObject("message") ?: return ""
        return contentToText(msg.opt("content")).trim()
    }

    fun contentToText(content: Any?): String = when (content) {
        null, JSONObject.NULL -> ""
        is String -> content
        is JSONArray -> {
            val sb = StringBuilder()
            for (i in 0 until content.length()) {
                val part = content.opt(i)
                when (part) {
                    is String -> sb.append(part)
                    is JSONObject -> {
                        val type = part.optString("type")
                        if (type == "text" || type.isEmpty()) {
                            sb.append(part.optString("text"))
                        }
                    }
                }
            }
            sb.toString()
        }
        is JSONObject -> content.optString("text")
        else -> content.toString()
    }

    fun parseThree(content: String): List<String> {
        val start = content.indexOf('[')
        val end = content.lastIndexOf(']')
        if (start >= 0 && end > start) {
            try {
                val arr = JSONArray(content.substring(start, end + 1))
                val out = ArrayList<String>()
                for (i in 0 until arr.length()) out.add(arr.getString(i).trim())
                if (out.size >= 3) return out.take(3)
                while (out.size < 3) out.add("（稍等，我看下）")
                return out
            } catch (_: Exception) { }
        }
        val lines = content.split("\n").map {
            it.trim().trimStart('-', '*', '1', '2', '3', '.', ' ', '"')
        }.filter { it.isNotBlank() }
        val out = lines.take(3).toMutableList()
        while (out.size < 3) out.add("（稍等，我看下）")
        return out
    }
}
