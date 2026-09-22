package com.jev.probe

import com.jev.probe.core.Affect
import com.jev.probe.core.Analysis
import com.jev.probe.core.BubbleBound
import com.jev.probe.core.CapturePace
import com.jev.probe.core.ChatGeometry
import com.jev.probe.core.ChatHistory
import com.jev.probe.core.ChatKind
import com.jev.probe.core.ChatMood
import com.jev.probe.core.ChatRel
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Choice
import com.jev.probe.core.DraftSteer
import com.jev.probe.core.GroupAuto
import com.jev.probe.core.GroupChat
import com.jev.probe.core.Msg
import com.jev.probe.core.OemSettings
import com.jev.probe.core.PanelCue
import com.jev.probe.core.Prefs
import com.jev.probe.core.Score
import com.jev.probe.core.WeChatSkip
import com.jev.probe.reply.ReplyParser
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertEquals(GroupAuto.OFF, GroupAuto.from(null))
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

class WeChatGreenTest {
    private val classic = 0xFF95EC69.toInt()
    private val night = 0xFF2BA65A.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val lightGray = 0xFFF7F7F7.toInt()
    private val ink = 0xFF191919.toInt()
    private val beigeWallpaper = 0xFFEDE0C8.toInt()

    @Test fun classicGreenIsOwn() {
        assertTrue(ChatGeometry.isWeChatOwnGreen(classic))
        assertFalse(ChatGeometry.isWeChatOtherBubble(classic))
    }

    @Test fun nightGreenIsOwn() {
        assertTrue(ChatGeometry.isWeChatOwnGreen(night))
        assertFalse(ChatGeometry.isWeChatOtherBubble(night))
    }

    @Test fun whiteIsOther() {
        assertTrue(ChatGeometry.isWeChatOtherBubble(white))
        assertFalse(ChatGeometry.isWeChatOwnGreen(white))
    }

    @Test fun lightGrayIsOther() {
        assertTrue(ChatGeometry.isWeChatOtherBubble(lightGray))
    }

    @Test fun beigeWallpaperIsNotABubble() {
        assertFalse(ChatGeometry.isWeChatOwnGreen(beigeWallpaper))
        assertFalse(ChatGeometry.isWeChatOtherBubble(beigeWallpaper))
    }

    @Test fun oneGreenBeatsWallpaperGray() {
        val wallpaper = 0xFFEDEDED.toInt()
        assertEquals("me", ChatGeometry.sideFromWeChatColors(listOf(wallpaper, classic, ink)))
    }

    @Test fun whiteSamplesAreOther() {
        assertEquals("other", ChatGeometry.sideFromWeChatColors(listOf(white, white, ink)))
    }

    @Test fun inkOnlyIsUnknown() {
        assertNull(ChatGeometry.sideFromWeChatColors(listOf(ink, ink)))
    }
}

class DecideSideTest {
    @Test fun nameAboveIsAlwaysOther() {
        assertEquals("other", ChatGeometry.decideSide(true, "me", "me", "me"))
    }

    @Test fun avatarBeatsColor() {
        assertEquals("me", ChatGeometry.decideSide(false, "me", "other", "other"))
        assertEquals("other", ChatGeometry.decideSide(false, "other", "me", "me"))
    }

    @Test fun colorWhenNoAvatar() {
        assertEquals("me", ChatGeometry.decideSide(false, null, "me", "other"))
    }

    @Test fun clusterLast() {
        assertEquals("other", ChatGeometry.decideSide(false, null, null, "other"))
        assertEquals("me", ChatGeometry.decideSide(false, null, null, "me"))
    }

    @Test fun avatarFromCx() {
        assertEquals("me", ChatGeometry.avatarSideFromCx(980, 1080))
        assertEquals("other", ChatGeometry.avatarSideFromCx(60, 1080))
        assertNull(ChatGeometry.avatarSideFromCx(540, 1080))
    }

    @Test fun gutterPhotoVsFlat() {
        assertEquals("other", ChatGeometry.avatarSideFromGutterVars(900.0, 20.0))
        assertEquals("me", ChatGeometry.avatarSideFromGutterVars(20.0, 900.0))
        assertNull(ChatGeometry.avatarSideFromGutterVars(10.0, 10.0))
        assertNull(ChatGeometry.avatarSideFromGutterVars(800.0, 700.0))
        assertEquals("other", ChatGeometry.avatarSideFromGutterVars(2000.0, 400.0))
    }

    @Test fun flatColorsHaveNoVariance() {
        val gray = List(8) { 0xFFEDEDED.toInt() }
        assertEquals(0.0, ChatGeometry.luminanceVar(gray), 0.01)
    }

    @Test fun mixedFaceLikeHasVariance() {
        val face = listOf(
            0xFFC48A6A.toInt(), 0xFF8D5A3C.toInt(), 0xFFE8C4A8.toInt(),
            0xFF3A2A20.toInt(), 0xFFD4A574.toInt(), 0xFF6B3F2A.toInt(),
            0xFFF0D0B8.toInt(), 0xFF2A1C14.toInt()
        )
        assertTrue(ChatGeometry.luminanceVar(face) > 350.0)
    }
}

class ChatHistoryTest {
    private fun m(side: String, text: String, speaker: String? = null) = Msg(side, text, speaker)

    @Test fun firstScreenIsVisible() {
        val vis = listOf(m("other", "你好"), m("me", "在"))
        assertEquals(vis, ChatHistory.merge(emptyList(), vis))
    }

    @Test fun appendNewTail() {
        val prev = listOf(m("other", "A"), m("other", "B"), m("me", "C"))
        val vis = listOf(m("other", "B"), m("me", "C"), m("other", "D"))
        assertEquals(
            listOf(m("other", "A"), m("other", "B"), m("me", "C"), m("other", "D")),
            ChatHistory.merge(prev, vis)
        )
    }

    @Test fun scrollUpPrepends() {
        val prev = listOf(m("other", "B"), m("me", "C"), m("other", "D"))
        val vis = listOf(m("other", "A"), m("other", "B"), m("me", "C"))
        assertEquals(
            listOf(m("other", "A"), m("other", "B"), m("me", "C"), m("other", "D")),
            ChatHistory.merge(prev, vis)
        )
    }

    @Test fun sameWindowNoDup() {
        val prev = listOf(m("other", "A"), m("me", "B"), m("other", "C"))
        val vis = listOf(m("me", "B"), m("other", "C"))
        assertEquals(prev, ChatHistory.merge(prev, vis))
    }

    @Test fun windowInsideHistory() {
        val prev = listOf(m("other", "A"), m("me", "B"), m("other", "C"), m("me", "D"))
        val vis = listOf(m("me", "B"), m("other", "C"))
        assertEquals(prev, ChatHistory.merge(prev, vis))
    }

    @Test fun capKeepsNewest() {
        val prev = (1..38).map { m("other", "t$it") }
        val vis = listOf(m("other", "t37"), m("other", "t38"), m("other", "t39"), m("other", "t40"), m("other", "t41"))
        val out = ChatHistory.merge(prev, vis, cap = 40)
        assertEquals(40, out.size)
        assertEquals("t2", out.first().text)
        assertEquals("t41", out.last().text)
    }

    @Test fun switchChatDoesNotMergeHere() {
        assertEquals("com.tencent.mm|工作群", ChatHistory.chatKey("com.tencent.mm", "工作群"))
        assertEquals("com.tencent.mm|", ChatHistory.chatKey("com.tencent.mm", "  "))
        assertNull(ChatHistory.chatKey(null, "x"))
    }

    @Test fun calibratedPinkIsOwn() {
        val pink = 0xFFF48FB1.toInt()
        assertTrue(ChatGeometry.colorClose(pink, 0xFFF178A0.toInt()))
        assertEquals("me", ChatGeometry.sideFromWeChatColors(listOf(pink, pink), pink))
        assertEquals("other", ChatGeometry.sideFromWeChatColors(listOf(0xFFFFFFFF.toInt()), pink))
    }
}

class CapturePaceTest {
    private fun m(side: String, text: String, speaker: String? = null) = Msg(side, text, speaker)
    private val box = BubbleBound(10, 20, 80, 60)

    @Test fun textIgnoresSide() {
        val a = CapturePace.textOf("周工", listOf(m("other", "在吗")))
        val b = CapturePace.textOf("周工", listOf(m("me", "在吗")))
        assertEquals(a, b)
        assertFalse(a == CapturePace.textOf("周工", listOf(m("other", "在吗"), m("me", "在"))))
    }

    @Test fun lockedBoundStays() {
        val msgs = listOf(m("other", "你好", "周工"))
        val out = CapturePace.recolor(msgs, listOf(null), { "me" }, { "me" })
        assertEquals("other", out[0].side)
        assertEquals("周工", out[0].speaker)
    }

    @Test fun colorFillsOpenBound() {
        val msgs = listOf(m("other", "你好"))
        val out = CapturePace.recolor(msgs, listOf(box), { null }, { "me" })
        assertEquals("me", out[0].side)
        assertNull(out[0].speaker)
    }

    @Test fun avatarBeatsColorOnOpenBound() {
        val msgs = listOf(m("me", "嗯"))
        val out = CapturePace.recolor(msgs, listOf(box), { "other" }, { "me" })
        assertEquals("other", out[0].side)
    }
}

class WeChatSkipTest {
    @Test fun fileHelper() {
        assertTrue(WeChatSkip.isSystemTitle("文件传输助手"))
        assertTrue(WeChatSkip.isSystemTitle(" File Transfer "))
        assertEquals("system", WeChatSkip.reason("文件传输助手", false))
    }

    @Test fun weChatOwnAccounts() {
        assertTrue(WeChatSkip.isSystemTitle("微信团队"))
        assertTrue(WeChatSkip.isSystemTitle("微信支付"))
        assertTrue(WeChatSkip.isSystemTitle("服务通知"))
        assertTrue(WeChatSkip.isSystemTitle("订阅号消息"))
        assertFalse(WeChatSkip.isSystemTitle("张三"))
        assertFalse(WeChatSkip.isSystemTitle("微信客服群"))
    }

    @Test fun officialChrome() {
        assertTrue(WeChatSkip.isOfficialChrome("", "公众号", ""))
        assertTrue(WeChatSkip.isOfficialChrome("", null, "人民日报公众号"))
        assertTrue(WeChatSkip.isOfficialChrome("com.tencent.mm:id/brand_service_menu", null, ""))
        assertFalse(WeChatSkip.isOfficialChrome("com.tencent.mm:id/bkl", "你好", ""))
        assertEquals("official", WeChatSkip.reason("人民日报", true))
        assertEquals("official", WeChatSkip.reason("某某的公众号", false))
    }

    @Test fun normalDmNotSkipped() {
        assertNull(WeChatSkip.reason("周工", false))
        assertNull(WeChatSkip.reason("项目组(9)", false))
    }

    @Test fun momentsAndTabsAreNotChat() {
        assertEquals("not_chat", WeChatSkip.pageSkip("朋友圈", false, 0, false, false))
        assertEquals("not_chat", WeChatSkip.pageSkip("Moments", false, 0, false, false))
        assertEquals("not_chat", WeChatSkip.pageSkip("微信", false, 0, false, false))
        assertEquals("not_chat", WeChatSkip.pageSkip("发现", false, 0, false, false))
        assertEquals("not_chat", WeChatSkip.pageSkip("周工", true, 0, false, false))
        assertEquals("not_chat", WeChatSkip.pageSkip("周工", false, 3, false, false))
        assertEquals("not_chat", WeChatSkip.pageSkip("周工", false, 0, true, false))
        assertEquals("not_chat", WeChatSkip.pageSkip(null, false, 0, false, true))
        assertNull(WeChatSkip.pageSkip("周工", false, 1, false, false))
        assertNull(WeChatSkip.pageSkip("项目组(9)", false, 0, false, false))
    }

    @Test fun momentsChromeAndComposer() {
        assertTrue(WeChatSkip.looksLikeMoments("com.tencent.mm:id/sns_timeline", null, "", 400, 2400))
        assertTrue(WeChatSkip.looksLikeMoments("", "朋友圈", "", 80, 2400))
        assertFalse(WeChatSkip.looksLikeMoments("", "今天发了朋友圈", "", 800, 2400))
        assertTrue(WeChatSkip.looksLikeFinder("com.tencent.mm:id/finder_feed", null, "", 400, 2400))
        assertTrue(WeChatSkip.looksLikeFinder("", "视频号", "", 60, 2400))
        assertFalse(WeChatSkip.looksLikeFinder("", "视频号", "", 900, 2400))
        assertTrue(WeChatSkip.isChatComposer(true, null, 2000, 2400))
        assertFalse(WeChatSkip.isChatComposer(true, null, 400, 2400))
        assertTrue(WeChatSkip.isChatComposer(false, "按住 说话", 2100, 2400))
    }
}

class AffectSteerTest {
    private fun analysis(
        affect: Choice?,
        tension: Double? = 0.2,
        danger: Double = 1.0,
        needs: String = "care",
        action: String = "acknowledge"
    ) = Analysis(
        trueIntent = null,
        dangerLevel = Score(danger, 0.9, 9),
        sheNeeds = Choice(needs, 0.9, emptyMap()),
        shouldReplyNow = null,
        bestAction = Choice(action, 0.8, emptyMap()),
        tensionResolved = tension,
        literalQuestion = null,
        rankedReplies = emptyList(),
        latencyMs = 1,
        affect = affect
    )

    @Test fun confidenceGateAndMemory() {
        assertNull(Affect.usable(Choice("hurt", 0.4, emptyMap())))
        assertNull(Affect.usable(Choice("nope", 0.9, emptyMap())))
        assertEquals("hurt", Affect.usable(Choice("hurt", 0.8, emptyMap()))?.choice)
        assertEquals("hurt", Affect.remember(ChatMood("angry"), Choice("hurt", 0.8, emptyMap()), 0.2)?.affect)
        assertEquals("angry", Affect.remember(ChatMood("angry"), Choice("steady", 0.2, emptyMap()), 0.2)?.affect)
        assertNull(Affect.remember(ChatMood("angry"), Choice("steady", 0.9, emptyMap()), 0.8))
    }

    @Test fun draftUsesConfidentAffectOrPrior() {
        val sure = DraftSteer.line(analysis(Choice("hurt", 0.8, emptyMap())), ChatMood("angry"), false)
        assertTrue(sure.contains("对方现在委屈"))
        assertFalse(sure.contains("上一轮"))
        assertTrue(sure.contains("不要开玩笑"))
        val vague = DraftSteer.line(analysis(Choice("hurt", 0.2, emptyMap())), ChatMood("angry"), false)
        assertFalse(vague.contains("委屈"))
        assertTrue(vague.contains("上一轮还是生气"))
        val cool = DraftSteer.line(
            analysis(Choice("hurt", 0.2, emptyMap()), tension = 0.9, needs = "nothing", action = "say_less"),
            ChatMood("angry"),
            false
        )
        assertFalse(cool.contains("生气"))
        assertFalse(cool.contains("不要开玩笑"))
        assertTrue(cool.contains("不需要你多做"))
    }

    @Test fun rankHintDropsWeakAffect() {
        val low = analysis(Choice("hurt", 0.2, emptyMap()))
        val hint = DraftSteer.rankHint(low, ChatMood("cold"))
        assertNull(hint.judgedAffect)
        assertEquals("cold", hint.priorAffect)
        val high = DraftSteer.rankHint(analysis(Choice("hurt", 0.9, emptyMap())), ChatMood("cold"))
        assertEquals("hurt", high.judgedAffect)
        assertNull(high.priorAffect)
    }

    @Test fun perChatRelationshipCap() {
        var map = ChatRel.put(emptyMap(), "a", "同事")
        assertEquals("同事", map["a"])
        assertFalse(ChatRel.put(map, "a", "  ").containsKey("a"))
        for (i in 1..40) map = ChatRel.put(map, "k$i", "v$i")
        assertEquals(ChatRel.MAX, map.size)
        assertFalse(map.containsKey("k1"))
        assertEquals("v40", ChatRel.decode(ChatRel.encode(map))["k40"])
    }
}

class PanelCueTest {
    private fun analysis(danger: Double, replyNow: Double?, affect: String? = null) = Analysis(
        trueIntent = null,
        dangerLevel = Score(danger, 0.9, 9),
        sheNeeds = null,
        shouldReplyNow = replyNow,
        bestAction = Choice("acknowledge", 0.8, emptyMap()),
        tensionResolved = 0.2,
        literalQuestion = null,
        rankedReplies = emptyList(),
        latencyMs = 1,
        affect = affect?.let { Choice(it, 0.9, emptyMap()) }
    )

    @Test fun autoOpenOnlyWhenItMatters() {
        assertFalse(PanelCue.shouldAutoOpen(analysis(2.0, 0.2)))
        assertTrue(PanelCue.shouldAutoOpen(analysis(6.0, 0.1)))
        assertTrue(PanelCue.shouldAutoOpen(analysis(1.0, 0.6)))
        assertFalse(PanelCue.shouldAutoOpen(analysis(1.0, null)))
    }

    @Test fun bubbleAndSummary() {
        val hurt = analysis(2.0, 0.2, "hurt")
        assertEquals(PanelCue.Tone.HOT, PanelCue.tone(hurt, null))
        assertEquals("委屈", PanelCue.bubbleText(false, false, hurt, null))
        assertEquals("@", PanelCue.bubbleText(true, true, null, null))
        assertEquals("!", PanelCue.bubbleText(false, true, null, null))
        assertEquals("委屈 · 接住情绪 · 先别给实质", PanelCue.summary(hurt, null, false))
        assertEquals(PanelCue.Tone.WATCH, PanelCue.tone(analysis(4.0, 0.2), null))
        assertEquals(PanelCue.Tone.DANGER, PanelCue.tone(analysis(8.0, 0.2), null))
        assertEquals(PanelCue.Tone.SAFE, PanelCue.tone(analysis(1.0, 0.2), null))
    }
}
