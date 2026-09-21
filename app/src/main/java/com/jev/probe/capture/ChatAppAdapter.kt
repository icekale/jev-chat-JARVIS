package com.jev.probe.capture

import android.content.res.Resources
import android.graphics.Rect
import android.util.TypedValue
import android.view.accessibility.AccessibilityNodeInfo
import com.jev.probe.core.ChatGeometry
import com.jev.probe.core.ChatKind
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.GroupChat
import com.jev.probe.core.Msg
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Per-app capture rules. An adapter turns one messaging app's open chat window
 * into a neutral [ChatSnapshot]; everything downstream (Jev judgment, overlay,
 * fill) is app-agnostic. [extract] returns null when the current window is not
 * that app's chat.
 */
interface ChatAppAdapter {
    val pkg: String
    fun extract(root: AccessibilityNodeInfo, res: Resources): ChatSnapshot?
}

internal inline fun walkNodes(
    root: AccessibilityNodeInfo,
    max: Int = 4000,
    visitor: (AccessibilityNodeInfo) -> Unit
) {
    val stack = ArrayDeque<AccessibilityNodeInfo>()
    stack.addLast(root)
    var guard = 0
    while (stack.isNotEmpty() && guard < max) {
        guard++
        val node = stack.removeLast()
        try {
            visitor(node)
            for (i in node.childCount - 1 downTo 0) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
        } finally {
            if (node !== root) runCatching { node.recycle() }
        }
    }
}

private fun looksLikeTimestamp(t: String): Boolean =
    TS_CLOCK.containsMatchIn(t) || TS_DATE.containsMatchIn(t) || t == "昨天" || t == "今天"

private val TS_CLOCK = Regex("""\d{1,2}[:：]\d{2}""")
private val TS_DATE = Regex("""\d+月\d+日""")

private fun dp(res: Resources, v: Int) = TypedValue.applyDimension(
    TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), res.displayMetrics
).roundToInt()

private data class Bubble(val top: Int, val bottom: Int, val cx: Int, val left: Int, val text: String)
private data class Label(val top: Int, val bottom: Int, val cx: Int, val left: Int, val text: String)

private fun attachSpeakers(
    bubbles: List<Bubble>,
    labels: List<Label>,
    mid: Int,
    nameGapPx: Int
): List<Msg> {
    return bubbles.map { b ->
        val side = if (b.cx > mid) "me" else "other"
        val speaker = if (side == "other") {
            labels.filter { lab ->
                lab.bottom <= b.top + 6 &&
                    b.top - lab.bottom in 0..nameGapPx &&
                    abs(lab.cx - b.cx) < (b.cx - b.left + 80)
            }.minByOrNull { b.top - it.bottom }?.text
        } else null
        Msg(side, b.text, speaker)
    }
}

private fun finishSnapshot(
    title: String?,
    bubbles: List<Bubble>,
    labels: List<Label>,
    width: Int,
    nameGapPx: Int
): ChatSnapshot? {
    if (bubbles.isEmpty()) return null
    val sorted = bubbles.sortedBy { it.top }
    val mid = ChatGeometry.clusterMidX(sorted.map { it.cx }) ?: (width / 2)
    val msgs = attachSpeakers(sorted, labels, mid, nameGapPx)
    val speakers = msgs.mapNotNull { it.speaker }.filter { it.isNotBlank() }.toSet()
    val kind = if (GroupChat.isGroup(title, speakers)) ChatKind.GROUP else ChatKind.DM
    return ChatSnapshot(
        title = title,
        messages = msgs,
        kind = kind,
        memberCount = GroupChat.memberCount(title)
    )
}

/** WeChat (com.tencent.mm). Multiple bubble ids + shape heuristic; side from cluster mid. */
class WeChatAdapter : ChatAppAdapter {
    override val pkg = "com.tencent.mm"

    override fun extract(root: AccessibilityNodeInfo, res: Resources): ChatSnapshot? {
        val width = res.displayMetrics.widthPixels
        val height = res.displayMetrics.heightPixels
        val actionBarMax = (height * 0.14).toInt()
        val bubbles = ArrayList<Bubble>()
        val labels = ArrayList<Label>()
        var title: String? = null
        var bestTop = Int.MAX_VALUE
        var firstBubbleTop = Int.MAX_VALUE

        walkNodes(root) { node ->
            val text = node.text?.toString()
            val b = Rect()
            node.getBoundsInScreen(b)
            if (isBubble(node, text, b, width, height, res)) {
                val t = text!!
                bubbles.add(Bubble(b.top, b.bottom, b.centerX(), b.left, t))
                if (b.top < firstBubbleTop) firstBubbleTop = b.top
            } else if (isNameLabel(node, text, b, width, height, res)) {
                labels.add(Label(b.top, b.bottom, b.centerX(), b.left, text!!.trim()))
            }
            if (!text.isNullOrBlank() && text.length <= 24 && !looksLikeTimestamp(text)) {
                val barMax = if (firstBubbleTop == Int.MAX_VALUE) actionBarMax
                else minOf(firstBubbleTop, actionBarMax)
                if (b.bottom in 1 until barMax && b.centerX() in (width / 4)..(width * 3 / 4)) {
                    if (b.top < bestTop) {
                        bestTop = b.top
                        title = text
                    }
                }
            }
        }
        return finishSnapshot(title, bubbles, labels, width, dp(res, 36))
    }

    private fun isNameLabel(
        node: AccessibilityNodeInfo,
        text: String?,
        b: Rect,
        width: Int,
        height: Int,
        res: Resources
    ): Boolean {
        if (text.isNullOrBlank()) return false
        if (node.isEditable) return false
        if (looksLikeTimestamp(text)) return false
        if (text.length !in 1..16) return false
        if (b.top < height * 0.12) return false
        if (b.height() > dp(res, 24)) return false
        if (b.left > width * 0.45) return false
        val cls = node.className?.toString() ?: ""
        return cls.contains("TextView") || cls.endsWith("Text")
    }

    private fun isBubble(
        node: AccessibilityNodeInfo,
        text: String?,
        b: Rect,
        width: Int,
        height: Int,
        res: Resources
    ): Boolean {
        if (text.isNullOrBlank()) return false
        if (node.isEditable) return false
        if (looksLikeTimestamp(text)) return false
        if (b.top < height * 0.12) return false
        if (b.width() < width * 0.12 || b.width() > width * 0.88) return false
        if (b.height() < dp(res, 18) || b.height() > height * 0.45) return false
        val id = node.viewIdResourceName
        if (id != null && BUBBLE_IDS.contains(id)) return true
        if (text.length <= 16 && b.height() < dp(res, 22)) return false
        val cls = node.className?.toString() ?: ""
        val looksText = cls.contains("TextView") || cls.endsWith("Text")
        if (!looksText || node.childCount != 0) return false
        val leftGutter = b.left > width * 0.04 && b.right < width * 0.78
        val rightGutter = b.left > width * 0.22 && b.right < width * 0.96
        return leftGutter || rightGutter
    }

    companion object {
        private val BUBBLE_IDS = setOf(
            "com.tencent.mm:id/bkl",
            "com.tencent.mm:id/bth",
            "com.tencent.mm:id/ekq",
            "com.tencent.mm:id/br1",
            "com.tencent.mm:id/nk1",
            "com.tencent.mm:id/jsb"
        )
    }
}

/** Feishu / Lark. Left as upstream wrote it; names ride along when present. */
class FeishuAdapter : ChatAppAdapter {
    override val pkg = "com.ss.android.lark"

    override fun extract(root: AccessibilityNodeInfo, res: Resources): ChatSnapshot? {
        val width = res.displayMetrics.widthPixels
        val height = res.displayMetrics.heightPixels
        val topBand = (height * 0.14).toInt()
        val bottomBand = (height * 0.84).toInt()

        var isChat = false
        var title: String? = null
        val bubbles = ArrayList<Bubble>()
        val labels = ArrayList<Label>()

        walkNodes(root, 6000) { node ->
            val id = node.viewIdResourceName ?: ""
            if (id.endsWith(":id/message") || id.endsWith(":id/bubble_content_container")) isChat = true
            if (id.endsWith(":id/group_name")) node.text?.toString()?.let { if (title == null) title = it }

            val text = node.text?.toString()
            val cls = node.className?.toString()
            val b = Rect(); node.getBoundsInScreen(b)
            if (id.endsWith(":id/name_tv") && !text.isNullOrBlank()) {
                labels.add(Label(b.top, b.bottom, b.centerX(), b.left, text.trim()))
                return@walkNodes
            }
            if (!text.isNullOrBlank() && cls == "android.widget.TextView" && !isChrome(id) && !looksLikeTimestamp(text)) {
                if (b.top in (topBand + 1) until bottomBand) {
                    bubbles.add(Bubble(b.top, b.bottom, b.centerX(), b.left, text.trim()))
                }
            }
        }
        if (!isChat) return null
        return finishSnapshot(title, bubbles, labels, width, dp(res, 40))
    }

    private fun isChrome(id: String): Boolean =
        id.endsWith(":id/group_name") ||
            id.endsWith(":id/name_tv") ||
            id.endsWith(":id/date_tv") ||
            id.endsWith(":id/system_label") ||
            id.endsWith(":id/kb_rich_text_content") ||
            id.endsWith(":id/thread_title_tv") ||
            id.endsWith(":id/thread_subtitle_tv")
}
