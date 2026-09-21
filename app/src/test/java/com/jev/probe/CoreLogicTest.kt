package com.jev.probe

import com.jev.probe.core.ChatGeometry
import com.jev.probe.core.OemSettings
import com.jev.probe.core.Prefs
import com.jev.probe.reply.ReplyParser
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatCompletionsUrlTest {
    @Test fun alreadyComplete() {
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            Prefs.chatCompletionsUrl("https://api.openai.com/v1/chat/completions")
        )
    }

    @Test fun v1Base() {
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            Prefs.chatCompletionsUrl("https://api.openai.com/v1")
        )
    }

    @Test fun hostOnly() {
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            Prefs.chatCompletionsUrl("https://api.openai.com")
        )
    }

    @Test fun trailingSlash() {
        assertEquals(
            "https://openrouter.ai/api/v1/chat/completions",
            Prefs.chatCompletionsUrl("https://openrouter.ai/api/v1/")
        )
    }

    @Test fun blankUsesDefault() {
        assertTrue(Prefs.chatCompletionsUrl("").endsWith("/chat/completions"))
    }
}

class ReplyParserTest {
    @Test fun stringContent() {
        val root = JSONObject()
            .put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", "hi"))))
        assertEquals("hi", ReplyParser.extractAssistantText(root))
    }

    @Test fun arrayContent() {
        val parts = JSONArray()
            .put(JSONObject().put("type", "text").put("text", "a"))
            .put(JSONObject().put("type", "text").put("text", "b"))
        val root = JSONObject()
            .put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", parts))))
        assertEquals("ab", ReplyParser.extractAssistantText(root))
    }

    @Test fun parseThreeJson() {
        val out = ReplyParser.parseThree("""ok ["一","二","三"] extra""")
        assertEquals(listOf("一", "二", "三"), out)
    }

    @Test fun parseThreePads() {
        val out = ReplyParser.parseThree("""["only"]""")
        assertEquals(3, out.size)
        assertEquals("only", out[0])
    }

    @Test fun parseThreeLines() {
        val out = ReplyParser.parseThree("1. 先这样\n2. 再那样\n3. 最后一句")
        assertEquals(listOf("先这样", "再那样", "最后一句"), out)
    }
}

class MergeComponentTest {
    @Test fun appends() {
        assertEquals(
            "a:b",
            OemSettings.mergeA11yComponent("a", "b")
        )
    }

    @Test fun idempotent() {
        val c = OemSettings.PLAIN_COMPONENT
        assertEquals(c, OemSettings.mergeA11yComponent(c, c))
        assertEquals("x:$c", OemSettings.mergeA11yComponent("x", c))
    }

    @Test fun skipsBlank() {
        assertEquals(
            OemSettings.PLAIN_COMPONENT,
            OemSettings.mergeA11yComponent("  :  ", OemSettings.PLAIN_COMPONENT)
        )
    }
}

class ClusterMidTest {
    @Test fun twoSides() {
        val mid = ChatGeometry.clusterMidX(listOf(80, 90, 700, 710))
        assertEquals(395, mid)
    }

    @Test fun oneSide() {
        assertNull(ChatGeometry.clusterMidX(listOf(80, 90, 100)))
    }

    @Test fun single() {
        assertNull(ChatGeometry.clusterMidX(listOf(200)))
    }
}
