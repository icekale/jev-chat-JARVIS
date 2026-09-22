package com.jev.probe.capture

import android.content.res.Resources
import android.graphics.Rect
import android.util.TypedValue
import android.view.accessibility.AccessibilityNodeInfo
import com.jev.probe.core.BubbleBound
import com.jev.probe.core.BubbleRect
import com.jev.probe.core.ChatGeometry
import com.jev.probe.core.ChatKind
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.GroupChat
import com.jev.probe.core.Msg
import com.jev.probe.core.WeChatSkip
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
    fun extract(
        root: AccessibilityNodeInfo,
        res: Resources,
        sampler: PixelSampler? = null
    ): ChatSnapshot?
}

internal inline fun walkNodes(
    root: AccessibilityNodeInfo,
    max: Int = 4000,
    stop: () -> Boolean = { false },
    visitor: (AccessibilityNodeInfo) -> Unit
) {
    val stack = ArrayDeque<AccessibilityNodeInfo>()
    stack.addLast(root)
    var guard = 0
    while (stack.isNotEmpty() && guard < max && !stop()) {
        guard++
        val node = stack.removeLast()
        try {
            visitor(node)
            if (stop()) break
            for (i in node.childCount - 1 downTo 0) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
        } finally {
            if (node !== root) runCatching { node.recycle() }
        }
    }
    while (stack.isNotEmpty()) {
        val extra = stack.removeLast()
        if (extra !== root) runCatching { extra.recycle() }
    }
}

private fun looksLikeTimestamp(t: String): Boolean =
    TS_CLOCK.containsMatchIn(t) || TS_DATE.containsMatchIn(t) || t == "昨天" || t == "今天"

private val TS_CLOCK = Regex("""\d{1,2}[:：]\d{2}""")
private val TS_DATE = Regex("""\d+月\d+日""")

private fun dp(res: Resources, v: Int) = TypedValue.applyDimension(
    TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), res.displayMetrics
).roundToInt()

private data class Bubble(
    val top: Int,
    val bottom: Int,
    val cx: Int,
    val left: Int,
    val right: Int,
    val text: String
)
private data class Label(val top: Int, val bottom: Int, val cx: Int, val left: Int, val text: String)
private data class Avatar(val top: Int, val bottom: Int, val cx: Int, val left: Int, val right: Int)

private fun labelAbove(b: Bubble, labels: List<Label>, nameGapPx: Int): Label? =
    labels.filter { lab ->
        lab.bottom <= b.top + 6 &&
            b.top - lab.bottom in 0..nameGapPx &&
            abs(lab.cx - b.cx) < (b.cx - b.left + 80)
    }.minByOrNull { b.top - it.bottom }

private fun avatarBeside(b: Bubble, avatars: List<Avatar>, width: Int): Avatar? =
    avatars.filter { av ->
        val overlap = minOf(av.bottom, b.bottom) - maxOf(av.top, b.top)
        val linedUp = overlap >= 8 || abs(av.top - b.top) <= 16
        if (!linedUp) return@filter false
        val leftAv = av.cx < width * 0.45
        if (leftAv) b.left >= av.right - 8 && b.left <= av.right + 96
        else b.right <= av.left + 8 && b.right >= av.left - 96
    }.minByOrNull { abs(it.top - b.top) }

private data class Spoken(val msg: Msg, val recolor: BubbleBound?)

private fun attachSpeakers(
    bubbles: List<Bubble>,
    labels: List<Label>,
    avatars: List<Avatar>,
    mid: Int,
    width: Int,
    nameGapPx: Int,
    sampler: PixelSampler?,
    useGreen: Boolean
): List<Spoken> {
    return bubbles.map { b ->
        val name = labelAbove(b, labels, nameGapPx)
        val byNode = avatarBeside(b, avatars, width)?.let { ChatGeometry.avatarSideFromCx(it.cx, width) }
        val byShot = if (byNode == null && useGreen) sampler?.weChatAvatarSide(b.top, b.bottom) else null
        val byAvatar = byNode ?: byShot
        val byColor = if (useGreen) sampler?.weChatSide(b.left, b.top, b.right, b.bottom) else null
        val cluster = if (b.cx > mid) "me" else "other"
        val side = ChatGeometry.decideSide(name != null, byAvatar, byColor, cluster)
        val speaker = if (side == "other") name?.text else null
        val open = useGreen && sampler == null && name == null && byNode == null
        val bound = if (open) BubbleBound(b.left, b.top, b.right, b.bottom) else null
        Spoken(Msg(side, b.text, speaker), bound)
    }
}

private fun finishSnapshot(
    title: String?,
    bubbles: List<Bubble>,
    labels: List<Label>,
    width: Int,
    nameGapPx: Int,
    sampler: PixelSampler? = null,
    useGreen: Boolean = false,
    avatars: List<Avatar> = emptyList(),
    skipReason: String? = null
): ChatSnapshot? {
    if (bubbles.isEmpty()) {
        if (skipReason == null) return null
        return ChatSnapshot(title = title, messages = emptyList(), skipReason = skipReason)
    }
    val sorted = bubbles.sortedBy { it.top }
    val mid = ChatGeometry.clusterMidX(sorted.map { it.cx }) ?: (width / 2)
    val spoken = attachSpeakers(sorted, labels, avatars, mid, width, nameGapPx, sampler, useGreen)
    val msgs = spoken.map { it.msg }
    val speakers = msgs.mapNotNull { it.speaker }.filter { it.isNotBlank() }.toSet()
    val kind = if (GroupChat.isGroup(title, speakers)) ChatKind.GROUP else ChatKind.DM
    val last = sorted.last()
    return ChatSnapshot(
        title = title,
        messages = msgs,
        kind = kind,
        memberCount = GroupChat.memberCount(title),
        lastBound = BubbleBound(last.left, last.top, last.right, last.bottom),
        lastVisibleText = msgs.lastOrNull()?.text,
        skipReason = skipReason,
        recolorBounds = spoken.map { it.recolor }
    )
}

/** WeChat (com.tencent.mm). Side: name-above → other, then avatar, then green, then X. */
class WeChatAdapter : ChatAppAdapter {
    override val pkg = PKG

    override fun extract(
        root: AccessibilityNodeInfo,
        res: Resources,
        sampler: PixelSampler?
    ): ChatSnapshot? {
        val width = res.displayMetrics.widthPixels
        val height = res.displayMetrics.heightPixels
        val actionBarMax = (height * 0.14).toInt()
        val bubbles = ArrayList<Bubble>()
        val labels = ArrayList<Label>()
        val avatars = ArrayList<Avatar>()
        var title: String? = null
        var bestTop = Int.MAX_VALUE
        var firstBubbleTop = Int.MAX_VALUE
        var officialChrome = false
        var momentsChrome = false
        var finderChrome = false
        var miniProgram = false
        var composer = false
        var knownBubble = false
        val tabs = HashSet<String>()
        var stopWalk = false

        walkNodes(root, stop = { stopWalk }) { node ->
            val text = node.text?.toString()
            val id = node.viewIdResourceName.orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            if (WeChatSkip.isOfficialChrome(id, text, desc)) officialChrome = true
            val b = Rect()
            node.getBoundsInScreen(b)
            if (WeChatSkip.looksLikeMoments(id, text, desc, b.top, height)) momentsChrome = true
            if (WeChatSkip.looksLikeFinder(id, text, desc, b.top, height)) finderChrome = true
            if (WeChatSkip.looksLikeMiniProgram(id)) miniProgram = true
            if (WeChatSkip.isChatComposer(node.isEditable, text, b.top, height)) composer = true
            if (id.isNotEmpty() && BUBBLE_IDS.contains(id)) knownBubble = true
            if (b.top > height * 0.80) {
                text?.trim()?.let { if (it in WeChatSkip.TAB_LABELS) tabs.add(it) }
            }
            if (momentsChrome || finderChrome || miniProgram || tabs.size >= 3) stopWalk = true
            if (isAvatar(node, text, b, width, height, res)) {
                avatars.add(Avatar(b.top, b.bottom, b.centerX(), b.left, b.right))
            } else if (isBubble(node, text, b, width, height, res)) {
                val t = text!!
                bubbles.add(Bubble(b.top, b.bottom, b.centerX(), b.left, b.right, t))
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
            if (title != null && WeChatSkip.isNonChatTitle(title)) stopWalk = true
        }
        val structural = WeChatSkip.pageSkip(title, momentsChrome, tabs.size, finderChrome, miniProgram)
        val skip = WeChatSkip.reason(title, officialChrome) ?: structural
            ?: if (!knownBubble && !composer) "not_chat" else null
        if (skip != null) {
            if (skip == "not_chat" && bubbles.isEmpty() && structural == null && !momentsChrome) {
                return null
            }
            return ChatSnapshot(title = title, messages = emptyList(), skipReason = skip)
        }
        return finishSnapshot(
            title, bubbles, labels, width, dp(res, 36), sampler,
            useGreen = true, avatars = avatars
        )
    }

    private fun isAvatar(
        node: AccessibilityNodeInfo,
        text: String?,
        b: Rect,
        width: Int,
        height: Int,
        res: Resources
    ): Boolean {
        if (!text.isNullOrBlank()) return false
        if (b.top < height * 0.12) return false
        val min = dp(res, 24)
        val max = dp(res, 72)
        if (b.width() !in min..max || b.height() !in min..max) return false
        val aspect = b.width().toFloat() / b.height().coerceAtLeast(1)
        if (aspect !in 0.75f..1.35f) return false
        val inLeft = b.right < width * 0.22
        val inRight = b.left > width * 0.78
        if (!inLeft && !inRight) return false
        val desc = node.contentDescription?.toString().orEmpty()
        val cls = node.className?.toString().orEmpty()
        if (cls.contains("Image") || desc.contains("头像") || desc.contains("avatar", ignoreCase = true)) {
            return true
        }
        // 8.0.52+ often hides class names; a gutter square with no text is still the avatar.
        return cls.isEmpty() || cls == "android.view.View"
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
        const val PKG = "com.tencent.mm"
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

    override fun extract(
        root: AccessibilityNodeInfo,
        res: Resources,
        sampler: PixelSampler?
    ): ChatSnapshot? {
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
                    bubbles.add(Bubble(b.top, b.bottom, b.centerX(), b.left, b.right, text.trim()))
                }
            }
        }
        if (!isChat) return null
        val rects = collectFeishuBubbleRects(root, res)
        if (bubbles.isEmpty()) {
            return ChatSnapshot(title = title, messages = emptyList(), bubbleRects = rects)
        }
        return finishSnapshot(title, bubbles, labels, width, dp(res, 40))?.copy(bubbleRects = rects)
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

internal fun findTitleInActionBar(
    root: AccessibilityNodeInfo,
    firstBubbleTop: Int,
    width: Int,
    res: Resources,
    minCenterRatio: Double = 0.25,
    maxCenterRatio: Double = 0.75
): String? {
    val actionBarMax = minOf(firstBubbleTop, (res.displayMetrics.heightPixels * 0.14).toInt())
    val minCenterX = (width * minCenterRatio).toInt()
    val maxCenterX = (width * maxCenterRatio).toInt()
    var best: String? = null
    var bestTop = Int.MAX_VALUE
    walkNodes(root, 5000) { node ->
        val text = node.text?.toString()
        if (!text.isNullOrBlank() && text.length <= 24 && !looksLikeTimestamp(text)) {
            val b = Rect(); node.getBoundsInScreen(b)
            if (b.bottom in 1 until actionBarMax && b.centerX() in minCenterX..maxCenterX && b.top < bestTop) {
                bestTop = b.top
                best = text
            }
        }
    }
    return best
}

private fun feishuHasReadState(bubble: AccessibilityNodeInfo): Boolean {
    val stack = ArrayDeque<AccessibilityNodeInfo>()
    stack.addLast(bubble)
    var guard = 0
    while (stack.isNotEmpty() && guard < 400) {
        guard++
        val node = stack.removeLast()
        val id = node.viewIdResourceName ?: ""
        if (id.endsWith("time_read_state_container_align_bubble")) return true
        for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it) }
    }
    return false
}

internal fun collectFeishuBubbleRects(root: AccessibilityNodeInfo, res: Resources): List<BubbleRect> {
    val height = res.displayMetrics.heightPixels
    val topBand = (height * 0.14).toInt()
    val bottomBand = (height * 0.84).toInt()
    val rects = ArrayList<BubbleRect>()
    walkNodes(root, 6000) { node ->
        val id = node.viewIdResourceName ?: ""
        if (id.endsWith(":id/bubble_content_container")) {
            val b = Rect(); node.getBoundsInScreen(b)
            if (b.width() > 0 && b.height() > 0 && b.bottom > topBand && b.top < bottomBand) {
                rects.add(BubbleRect(Rect(b), if (feishuHasReadState(node)) "me" else "other"))
            }
        }
    }
    rects.sortBy { it.rect.top }
    return rects
}

/** QQ 9.3.x. Side is which edge the bubble sits against. */
class QQAdapter : ChatAppAdapter {
    override val pkg = PKG

    override fun extract(
        root: AccessibilityNodeInfo,
        res: Resources,
        sampler: PixelSampler?
    ): ChatSnapshot? {
        val width = res.displayMetrics.widthPixels
        val bubbles = ArrayList<QQBubble>()
        var firstBubbleTop = Int.MAX_VALUE
        var title: String? = null
        var hasInput = false
        walkNodes(root, 5000) { node ->
            val id = node.viewIdResourceName
            val text = node.text?.toString()
            if (id == BUBBLE_ID && !text.isNullOrBlank()) {
                val b = Rect(); node.getBoundsInScreen(b)
                bubbles.add(QQBubble(b.top, b.left, b.right, text))
                if (b.top < firstBubbleTop) firstBubbleTop = b.top
            }
            if (!hasInput && id == INPUT_ID) hasInput = true
            if (id == TITLE_ID && title == null) text?.let { if (it.isNotBlank()) title = it }
        }
        if (bubbles.isEmpty() && !hasInput) return null
        if (title == null) title = findTitleInActionBar(root, firstBubbleTop, width, res)
        if (bubbles.isEmpty()) return ChatSnapshot(title, emptyList())
        val avatarEdge = (width * 0.13).toInt()
        bubbles.sortBy { it.top }
        val msgs = bubbles.map { b ->
            val dl = kotlin.math.abs(b.left - avatarEdge)
            val dr = kotlin.math.abs((width - avatarEdge) - b.right)
            Msg(if (dr < dl) "me" else "other", b.text)
        }
        return ChatSnapshot(title, msgs)
    }

    private data class QQBubble(val top: Int, val left: Int, val right: Int, val text: String)

    companion object {
        const val PKG = "com.tencent.mobileqq"
        private const val BUBBLE_ID = "com.tencent.mobileqq:id/mjn"
        private const val TITLE_ID = "com.tencent.mobileqq:id/371"
        private const val INPUT_ID = "com.tencent.mobileqq:id/input"
    }
}

private val X_TAIL_TIME = Regex("""\d{1,2}[:：]\d{2}\s*(上午|下午|AM|PM|am|pm)?$""")
private val X_TRAILING_DOTS = Regex("""。+$""")

private fun parseXDesc(desc: String): Pair<String, String>? {
    val full = desc.indexOf('：')
    val half = desc.indexOf(": ")
    val cut: Int
    val skip: Int
    when {
        full >= 0 && (half < 0 || full <= half) -> { cut = full; skip = 1 }
        half >= 0 -> { cut = half; skip = 2 }
        else -> return null
    }
    val sender = desc.substring(0, cut).trim()
    var body = desc.substring(cut + skip).trim()
    for (tail in arrayOf("Read。", "Read", "已读。", "已读")) {
        if (body.endsWith(tail)) { body = body.removeSuffix(tail).trim(); break }
    }
    body = X_TRAILING_DOTS.replace(body, "").trim()
    X_TAIL_TIME.find(body)?.let { body = body.substring(0, it.range.first).trim() }
    body = X_TRAILING_DOTS.replace(body, "").trim()
    if (sender.isEmpty() || body.isEmpty()) return null
    return sender to body
}

/** X / Twitter direct messages. Side comes from the sender label, not geometry. */
class XAdapter : ChatAppAdapter {
    override val pkg = PKG

    override fun extract(
        root: AccessibilityNodeInfo,
        res: Resources,
        sampler: PixelSampler?
    ): ChatSnapshot? {
        val width = res.displayMetrics.widthPixels
        val rows = ArrayList<XRow>()
        var firstRowTop = Int.MAX_VALUE
        var hasInput = false
        var hasMessageRowShape = false
        var hasDmLabel = false
        var hasNewDmMarker = false
        var sawListHeading = false
        walkNodes(root, 6000) { node ->
            val cls = node.className?.toString()
            if (!hasInput && (node.isEditable || cls == "android.widget.EditText")) hasInput = true
            val desc = node.contentDescription?.toString()
            if (desc == "新私信" || desc == "New message") hasNewDmMarker = true
            if (cls == "android.view.View" && !desc.isNullOrBlank() && !desc.contains(", @")) {
                val b = Rect(); node.getBoundsInScreen(b)
                if (b.left == 0 && b.right == width) {
                    if (desc.contains('：') || desc.contains(": ")) hasMessageRowShape = true
                    val parsed = parseXDesc(desc)
                    if (parsed != null) {
                        rows.add(XRow(b.top, parsed.first, parsed.second))
                        if (b.top < firstRowTop) firstRowTop = b.top
                    }
                }
            }
            if (cls == "android.widget.TextView") {
                val text = node.text?.toString()?.trim()
                if (text == "私信" || text == "Message" || text == "发送私信") hasDmLabel = true
                if (text == "聊天" || text == "Messages") sawListHeading = true
            }
        }
        if (hasNewDmMarker || (sawListHeading && rows.isEmpty())) return null
        if (!hasInput || !(hasMessageRowShape || hasDmLabel)) return null
        val title = findTitleInActionBar(root, firstRowTop, width, res, 0.15, 0.85)
        if (rows.isEmpty()) return ChatSnapshot(title, emptyList())
        rows.sortBy { it.top }
        val msgs = rows.map { Msg(if (it.sender == "你" || it.sender == "You") "me" else "other", it.text) }
        return ChatSnapshot(title, msgs)
    }

    private data class XRow(val top: Int, val sender: String, val text: String)

    companion object {
        const val PKG = "com.twitter.android"
    }
}
