package com.jev.probe

import com.jev.probe.core.ChatGeometry
import com.jev.probe.core.ChatKind
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.GroupAuto
import com.jev.probe.core.GroupChat
import com.jev.probe.core.Msg
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

class GroupChatTest {
    @Test fun titleCount() {
        assertEquals(8, GroupChat.memberCount("家人群(8)"))
        assertEquals(12, GroupChat.memberCount("项目组（12人）"))
        assertEquals(null, GroupChat.memberCount("小王"))
        assertEquals("家人群", GroupChat.displayTitle("家人群(8)"))
    }

    @Test fun detectGroup() {
        assertTrue(GroupChat.isGroup("家人群(8)", emptySet()))
        assertTrue(GroupChat.isGroup("周末约饭", listOf("张三", "李四")))
        assertTrue(!GroupChat.isGroup("小王", emptySet()))
    }

    @Test fun mentions() {
        assertTrue(GroupChat.mentionIn("@我 看一下", emptySet()))
        assertTrue(GroupChat.mentionIn("@所有人开会", emptySet()))
        assertTrue(GroupChat.mentionIn("@Kale 来一下", setOf("Kale")))
        assertTrue(!GroupChat.mentionIn("晚上吃什么", setOf("Kale")))
    }

    @Test fun autoPolicy() {
        val groupHit = ChatSnapshot(
            "家人群(8)",
            listOf(Msg("other", "@我 在吗", "张三")),
            ChatKind.GROUP,
            mentionedMe = true,
            memberCount = 8
        )
        val groupMiss = groupHit.copy(
            messages = listOf(Msg("other", "晚上吃什么", "张三")),
            mentionedMe = false
        )
        assertTrue(GroupChat.shouldAutoAnalyze(groupHit, true, GroupAuto.MENTION))
        assertTrue(!GroupChat.shouldAutoAnalyze(groupMiss, true, GroupAuto.MENTION))
        assertTrue(GroupChat.shouldAutoAnalyze(groupMiss, true, GroupAuto.ALL))
        assertTrue(!GroupChat.shouldAutoAnalyze(groupHit, true, GroupAuto.OFF))
        val dm = ChatSnapshot("小王", listOf(Msg("other", "在吗")))
        assertTrue(GroupChat.shouldAutoAnalyze(dm, true, GroupAuto.MENTION))
    }

    @Test fun contextOpenAsk() {
        val msgs = listOf(
            Msg("other", "@Kale 周五能来吗", "李四"),
            Msg("other", "晚上吃什么", "张三")
        )
        val ctx = GroupChat.context(msgs, setOf("Kale"), emptySet())
        assertTrue(ctx.openAsk?.contains("周五") == true)
        assertEquals("李四", ctx.openAskSpeaker)
        assertTrue(ctx.mentionedEarlier)
        assertTrue(!ctx.mentionedMe)
    }

    @Test fun contextWatchAndMoney() {
        val watch = GroupChat.context(
            listOf(Msg("other", "周报今晚交", "王五")),
            emptySet(),
            setOf("周报")
        )
        assertEquals(listOf("周报"), watch.watchHits)
        assertTrue(watch.relevantNow)
        val money = GroupChat.context(
            listOf(Msg("other", "[微信红包]", "张三")),
            emptySet(),
            emptySet()
        )
        assertTrue(money.moneyRelated)
        assertTrue(!money.relevantNow)
    }

    @Test fun applyAt() {
        val snap = ChatSnapshot(
            "项目组(9)",
            listOf(Msg("other", "你看一下接口", "周工")),
            ChatKind.GROUP,
            group = GroupChat.context(
                listOf(Msg("other", "你看一下接口", "周工")),
                emptySet(),
                emptySet()
            )
        )
        assertEquals("@周工 好的", GroupChat.applyAt("好的", snap, "latest_speaker", "reply_brief"))
        assertEquals("好的", GroupChat.applyAt("好的", snap, "latest_speaker", "wait"))
        assertEquals("@周工 已在", GroupChat.applyAt("@周工 已在", snap, "latest_speaker", "reply_brief"))
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
