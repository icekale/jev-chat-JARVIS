package com.jev.probe.jev

import android.util.Log
import com.jev.probe.core.Analysis
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Choice
import com.jev.probe.core.Prefs
import com.jev.probe.core.RankedReply
import com.jev.probe.core.Score
import com.jev.probe.reply.ReplyParser
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Talks to Jev (OpenRouter proxy or official TypeSafe `/v1/systemone`) for
 * typed judgments, then to any OpenAI-compatible Chat Completions endpoint
 * to draft 3 candidate replies and Jev-rank them.
 *
 * Keys are passed in per call; they are never logged.
 */
class JevClient(
    private val key: String,
    private val replyModel: String,
    private val provider: JevProvider = JevProvider.OPENROUTER,
    private val replyKey: String = "",
    private val replyBaseUrl: String = Prefs.DEFAULT_REPLY_BASE
) {

    private val decisionsUrl = provider.decisionsUrl
    private val jevModel = provider.jevModel
    private val chatUrl = Prefs.chatCompletionsUrl(replyBaseUrl)
    private val openRouterChat = Prefs.isOpenRouterChat(chatUrl)

    private fun chatAuthKey(): String = replyKey.ifBlank {
        if (openRouterChat && provider == JevProvider.OPENROUTER) key else ""
    }

    /** The 7 judgment questions only (fast, ~1s). No candidate generation. */
    fun judge(snapshot: ChatSnapshot, relationship: String): Analysis {
        val start = System.currentTimeMillis()
        try {
            val body = JSONObject()
                .put("model", jevModel)
                .put("state", JevQuestions.buildState(snapshot, relationship))
                .put("questions", JevQuestions.judge())
            val answers = postJson(decisionsUrl, body, key).optJSONObject("answers") ?: JSONObject()
            return Analysis(
                trueIntent = parseChoice(answers.optJSONObject("true_intent")),
                dangerLevel = parseScore(answers.optJSONObject("danger_level")),
                sheNeeds = parseChoice(answers.optJSONObject("she_needs")),
                shouldReplyNow = answers.optJSONObject("should_reply_now")?.optDouble("noul"),
                bestAction = parseChoice(answers.optJSONObject("best_action")),
                tensionResolved = answers.optJSONObject("tension_resolved")?.optDouble("noul"),
                literalQuestion = answers.optJSONObject("literal_question")?.optDouble("noul"),
                rankedReplies = emptyList(),
                latencyMs = System.currentTimeMillis() - start
            )
        } catch (e: Exception) {
            Log.w(TAG, "judge failed: ${e.message}")
            return Analysis(null, null, null, null, null, null, null, emptyList(),
                System.currentTimeMillis() - start, error = readableError(e))
        }
    }

    /** Draft 3 candidate replies (generative model) then Jev-rank them. Slower. */
    fun draftAndRank(snapshot: ChatSnapshot, relationship: String): List<RankedReply> {
        if (chatAuthKey().isBlank()) return emptyList()
        val candidates = generateCandidates(snapshot, relationship)
        val questions = JSONObject().put("best_reply",
            JevQuestions.rankQuestion(candidates).getJSONObject("best_reply"))
        val body = JSONObject()
            .put("model", jevModel)
            .put("state", JevQuestions.buildState(snapshot, relationship))
            .put("questions", questions)
        val answers = postJson(decisionsUrl, body, key).optJSONObject("answers") ?: JSONObject()
        return parseRanked(answers.optJSONObject("best_reply"), candidates)
    }

    /** Convenience for the settings connectivity test: judge + replies, sequential. */
    fun analyze(snapshot: ChatSnapshot, relationship: String): Analysis {
        val a = judge(snapshot, relationship)
        if (a.error != null) return a
        val ranked = try { draftAndRank(snapshot, relationship) } catch (e: Exception) { emptyList() }
        return a.copy(rankedReplies = ranked)
    }

    /** Ask a generative model for exactly 3 varied candidate replies (Chinese). */
    private fun generateCandidates(snapshot: ChatSnapshot, relationship: String): List<String> {
        val convo = snapshot.messages.takeLast(10).joinToString("\n") {
            (if (it.side == "me") "我" else "对方") + "：" + it.text
        }
        val sys = "你是中文即时通讯回复助手。只输出一个 JSON 数组，含且仅含 3 条候选回复文本，" +
            "三条策略要有区别（例如：一条稳妥承接、一条给具体行动或承诺、一条简短低姿态）。" +
            "每条不超过 40 字，口语、自然、像真人在聊天软件里发消息。不要解释，不要加引号以外的内容，直接输出 JSON 数组。"
        val user = "关系：$relationship\n\n最近对话：\n$convo\n\n请给出 3 条候选回复。"
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", sys))
            .put(JSONObject().put("role", "user").put("content", user))
        val body = JSONObject()
            .put("model", replyModel)
            .put("messages", messages)
            .put("temperature", 0.8)
        val resp = postJson(chatUrl, body, chatAuthKey(), extraOpenRouterHeaders = openRouterChat)
        val content = ReplyParser.extractAssistantText(resp)
        return ReplyParser.parseThree(content)
    }

    private fun parseChoice(o: JSONObject?): Choice? {
        o ?: return null
        val probs = HashMap<String, Double>()
        o.optJSONObject("probabilities")?.let { p ->
            p.keys().forEach { k -> probs[k] = p.optDouble(k) }
        }
        return Choice(o.optString("choice"), o.optDouble("confidence", 0.0), probs)
    }

    private fun parseScore(o: JSONObject?): Score? {
        o ?: return null
        val legend = o.optJSONObject("legend")
        val maxLevel = legend?.keys()?.asSequence()?.mapNotNull { it.toIntOrNull() }?.maxOrNull() ?: 9
        return Score(o.optDouble("score", 0.0), o.optDouble("confidence", 0.0), maxLevel)
    }

    private fun parseRanked(o: JSONObject?, candidates: List<String>): List<RankedReply> {
        val keys = listOf("reply_a", "reply_b", "reply_c")
        val probs = o?.optJSONObject("probabilities")
        val list = candidates.mapIndexed { i, text ->
            RankedReply(text, probs?.optDouble(keys.getOrElse(i) { "" }, 0.0) ?: 0.0)
        }
        return list.sortedByDescending { it.prob }
    }

    /** POST JSON with one retry chain for 429/529 (exponential backoff). */
    private fun postJson(
        urlStr: String,
        body: JSONObject,
        authKey: String,
        extraOpenRouterHeaders: Boolean = false
    ): JSONObject {
        var attempt = 0
        var lastErr: Exception? = null
        while (attempt < 3) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15000
                    readTimeout = 25000
                    doOutput = true
                    setRequestProperty("Authorization", "Bearer $authKey")
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "application/json")
                    if (extraOpenRouterHeaders ||
                        (urlStr.contains("openrouter.ai") && provider == JevProvider.OPENROUTER)
                    ) {
                        setRequestProperty("HTTP-Referer", "https://jev-assistant.local")
                        setRequestProperty("X-Title", "Jev Assistant")
                    }
                }
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                conn.outputStream.use { os: OutputStream -> os.write(bytes) }
                val code = conn.responseCode
                if (code == 429 || code == 529) {
                    attempt++
                    Thread.sleep(500L * (1L shl attempt))
                    continue
                }
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
                if (code !in 200..299) throw RuntimeException("HTTP $code: ${text.take(160)}")
                return JSONObject(text)
            } catch (e: Exception) {
                lastErr = e
                if (e.message?.contains("HTTP 4") == true) throw e // client error: no retry
                attempt++
                if (attempt < 3) Thread.sleep(500L * (1L shl attempt))
            } finally {
                conn?.disconnect()
            }
        }
        throw lastErr ?: RuntimeException("request failed")
    }

    private fun readableError(e: Exception): String {
        val m = e.message ?: e.javaClass.simpleName
        return when {
            m.contains("HTTP 401") ->
                if (provider == JevProvider.TYPESAFE) "TypeSafe 密钥无效或未设置（401）"
                else "OpenRouter 密钥无效或未设置（401）"
            m.contains("HTTP 4") -> "请求被拒：$m"
            m.contains("timed out") || m.contains("timeout") -> "网络超时，请检查连接"
            m.contains("Unable to resolve host") || m.contains("Failed to connect") -> "无法连接网络"
            else -> "分析失败：$m"
        }
    }

    companion object { private const val TAG = "JEVASSIST" }
}
