package com.jev.probe.overlay

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.jev.probe.core.Analysis
import com.jev.probe.core.ChatKind
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.GroupChat
import com.jev.probe.core.Prefs
import com.jev.probe.core.RankedReply
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Floating overlay: a small draggable bubble that expands into a translucent
 * panel showing Jev's read of the chat plus 3 ranked candidate replies. All
 * actions are copy / fill — never send.
 *
 * Design goals: let the chat show through (adjustable opacity), keep the signal
 * scannable (danger badge + intent headline + reply cards), and stay out of the
 * way (draggable bubble that snaps to the edge and remembers its position).
 */
class OverlayController(private val ctx: Context) {

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs = Prefs(ctx)
    private var root: FrameLayout? = null
    private var bubble: TextView? = null
    private var dangerDot: View? = null
    private var panel: LinearLayout? = null
    private var contentBox: LinearLayout? = null
    private var expanded = false
    private var lp: WindowManager.LayoutParams? = null

    var onManualAnalyze: (() -> Unit)? = null
    var onMarkAsMe: (() -> Unit)? = null

    /** Whether the overlay window is currently on screen. */
    fun isShowing(): Boolean = root?.isAttachedToWindow == true

    private var lastJudgment: Analysis? = null
    private var lastFill: ((String) -> Unit)? = null
    private var bubbleMenu: View? = null
    private var lastSnapshot: ChatSnapshot? = null

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).roundToInt()

    private fun canOverlay(): Boolean = Settings.canDrawOverlays(ctx)

    private val screenW get() = ctx.resources.displayMetrics.widthPixels
    private val screenH get() = ctx.resources.displayMetrics.heightPixels

    /** Panel background: white with the user's opacity so the chat shows through. */
    private fun panelBg(): Int {
        val a = (prefs.overlayOpacity / 100f * 255).roundToInt().coerceIn(150, 255)
        return Color.argb(a, 255, 255, 255)
    }

    private fun card(radius: Int, color: Int, stroke: Boolean = false) = GradientDrawable().apply {
        cornerRadius = dp(radius).toFloat()
        setColor(color)
        if (stroke) setStroke(dp(1), Color.parseColor("#22000000"))
    }

    // ---------------------------------------------------------------- window

    private fun ensureRoot() {
        val existing = root
        if (existing != null) {
            if (existing.isAttachedToWindow) return
            root = null
            bubble = null
            panel = null
            contentBox = null
            dangerDot = null
            bubbleMenu = null
            expanded = false
        }
        if (!canOverlay()) { android.util.Log.w("JEVASSIST", "overlay: canDrawOverlays=false"); return }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (prefs.bubbleX in 0..(screenW - dp(52))) prefs.bubbleX else dp(8)
            y = if (prefs.bubbleY >= 0) prefs.bubbleY else dp(150)
        }
        lp = params

        val r = FrameLayout(ctx)
        val p = buildPanel()
        val bubbleWrap = buildBubble(params)
        r.addView(p)
        r.addView(bubbleWrap)
        root = r
        try { wm.addView(r, params) } catch (e: Exception) {
            android.util.Log.e("JEVASSIST", "overlay addView failed: ${e.message}"); root = null
        }
    }

    private fun buildBubble(params: WindowManager.LayoutParams): View {
        val wrap = FrameLayout(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(dp(52), dp(52))
        }
        val b = TextView(ctx).apply {
            text = "Jev"
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(235, 58, 122, 254))
            }
            layoutParams = FrameLayout.LayoutParams(dp(52), dp(52))
        }
        val dot = View(ctx).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT) }
            layoutParams = FrameLayout.LayoutParams(dp(12), dp(12)).apply {
                gravity = Gravity.TOP or Gravity.END
            }
        }
        wrap.addView(b)
        wrap.addView(dot)
        attachBubbleTouch(wrap, params)
        bubble = b; dangerDot = dot
        return wrap
    }

    private fun buildPanel(): LinearLayout {
        val p = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = card(18, panelBg(), stroke = true)
            elevation = dp(8).toFloat()
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = FrameLayout.LayoutParams(dp(316), FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(56) // sit just below the bubble
            }
        }
        // Header
        val header = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(ctx).apply {
            text = "Jev 分析"; setTextColor(Color.parseColor("#111827")); textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(iconBtn("⚙") { openSettings() })
        header.addView(iconBtn("✕") { toggle() })
        p.addView(header)

        val scroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            // Cap the height so the panel stays in the upper area and does not
            // cover the WeChat input box / keyboard. Scroll inside if taller.
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (screenH * 0.40f).roundToInt()).apply { topMargin = dp(6) }
        }
        val content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)
        p.addView(scroll)
        contentBox = content
        panel = p
        return p
    }

    private fun iconBtn(glyph: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = glyph; setTextColor(Color.parseColor("#6B7280")); textSize = 16f
        setPadding(dp(10), dp(2), dp(6), dp(2))
        setOnClickListener { onClick() }
    }

    // --------------------------------------------------------------- gestures

    private fun attachBubbleTouch(v: View, params: WindowManager.LayoutParams) {
        var startX = 0; var startY = 0; var touchX = 0f; var touchY = 0f
        var moved = false; var downTime = 0L; var longFired = false
        val longPress = Runnable {
            if (!moved) { longFired = true; showBubbleMenu() }
        }
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y; touchX = e.rawX; touchY = e.rawY
                    moved = false; longFired = false; downTime = System.currentTimeMillis()
                    v.postDelayed(longPress, 500); true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - touchX).toInt(); val dy = (e.rawY - touchY).toInt()
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                    // Keep a margin from both side edges: the extreme edge is MIUI's
                    // back-gesture zone, which steals touches and makes the bubble
                    // "stuck". Free positioning (no forced edge snap) also avoids it.
                    params.x = (startX + dx).coerceIn(dp(8), screenW - dp(60))
                    params.y = (startY + dy).coerceIn(dp(24), screenH - dp(120))
                    root?.let { runCatching { wm.updateViewLayout(it, params) } }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.removeCallbacks(longPress)
                    if (longFired) { true }
                    else if (moved) {
                        prefs.bubbleX = params.x; prefs.bubbleY = params.y; true  // stays where dropped
                    } else { toggle(); true }
                }
                MotionEvent.ACTION_CANCEL -> { v.removeCallbacks(longPress); true }
                else -> false
            }
        }
    }

    private fun dismissMenu() {
        bubbleMenu?.let { m -> root?.removeView(m) }
        bubbleMenu = null
    }

    private fun showBubbleMenu() {
        dismissMenu()
        val menu = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = card(12, panelBg(), stroke = true)
            elevation = dp(8).toFloat()
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = FrameLayout.LayoutParams(dp(150), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(56) }
        }
        menu.addView(menuItem("打开设置") { openSettings(); dismissMenu() })
        menu.addView(menuItem("隐藏助手（本次）") { hide() })
        menu.addView(menuItem("取消") { dismissMenu() })
        bubbleMenu = menu
        root?.addView(menu)
    }

    private fun menuItem(label: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = label; setTextColor(Color.parseColor("#111827")); textSize = 14f
        setPadding(dp(12), dp(10), dp(12), dp(10)); setOnClickListener { onClick() }
    }

    private fun openSettings() {
        runCatching {
            ctx.startActivity(Intent().setClassName(ctx, "com.jev.probe.SettingsActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        if (expanded) toggle()
    }

    private var collapsedX = dp(6)

    private fun toggle() {
        expanded = !expanded
        val params = lp ?: return
        if (expanded) {
            collapsedX = params.x
            val panelW = dp(316)
            params.x = params.x.coerceIn(dp(6), (screenW - panelW - dp(8)).coerceAtLeast(dp(6)))
            val maxTop = (screenH * 0.45f).roundToInt()
            if (params.y > maxTop) params.y = maxTop
            panel?.visibility = View.VISIBLE
        } else {
            panel?.visibility = View.GONE
            params.x = collapsedX  // bubble returns to its edge
        }
        root?.let { runCatching { wm.updateViewLayout(it, params) } }
    }

    // ------------------------------------------------------------ public API

    fun showIdle(snapshot: ChatSnapshot) {
        lastSnapshot = snapshot
        ensureRoot(); bubble?.alpha = 0.55f
        if (snapshot.kind == ChatKind.GROUP) lastJudgment = null
        else if (lastJudgment != null) return
        val views = ArrayList<View>()
        if (snapshot.kind == ChatKind.GROUP) {
            val title = GroupChat.displayTitle(snapshot.title)
            val head = buildString {
                append("群聊 · ").append(title)
                snapshot.memberCount?.let { append(" · ").append(it).append("人") }
            }
            views.add(line(head, "#6B7280", 12f, true))
            val g = snapshot.group
            if (prefs.groupDigest && g != null && g.digest.isNotBlank()) {
                views.add(line(g.digest, "#111827", 13f))
            }
            if (g?.moneyRelated == true) {
                views.add(hint("涉及红包/转账，已跳过（不碰钱）"))
                views.addAll(transcriptViews(snapshot, 4))
                setContent(views)
                return
            }
        } else {
            snapshot.title?.takeIf { it.isNotBlank() }?.let {
                views.add(line(it, "#6B7280", 12f, true))
            }
        }
        views.addAll(transcriptViews(snapshot, 5))
        views.add(markAsMeRow())
        val label = when {
            snapshot.kind == ChatKind.GROUP && snapshot.mentionedMe -> "有人@你 · 分析"
            snapshot.group?.relevantNow == true -> "与你有关 · 分析"
            snapshot.kind == ChatKind.GROUP -> "群聊 · 点此分析"
            else -> "分析当前对话"
        }
        views.add(bigButton(label) { onManualAnalyze?.invoke() })
        setContent(views)
    }

    private fun bigButton(label: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = label; textSize = 14f; gravity = Gravity.CENTER
        setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD)
        background = card(12, Color.parseColor("#3A7AFE"))
        setPadding(dp(12), dp(11), dp(12), dp(11))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setOnClickListener { onClick() }
    }

    fun bindSnapshot(snapshot: ChatSnapshot) {
        lastSnapshot = snapshot
    }

    fun showLoading() {
        ensureRoot(); bubble?.alpha = 1f
        setContent(listOf(hint("分析中…")))
        if (!expanded) toggle()
    }

    fun showError(msg: String) {
        ensureRoot(); bubble?.alpha = 1f
        setContent(listOf(
            line("出错了", "#DC2626", 14f, true),
            hint(msg)))
    }

    fun showJudgment(a: Analysis) {
        lastJudgment = a
        render(a, generating = true)
    }

    fun showReplies(ranked: List<RankedReply>, onFill: (String) -> Unit) {
        lastFill = onFill
        val a = lastJudgment?.copy(rankedReplies = ranked) ?: return
        lastJudgment = a
        render(a, generating = false)
    }

    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

    fun forgetJudgment() {
        lastJudgment = null
    }

    fun hide() {
        val r = root ?: return
        runCatching { wm.removeView(r) }
        root = null; bubble = null; panel = null; contentBox = null; dangerDot = null
        bubbleMenu = null; expanded = false
        lastJudgment = null; lastFill = null; lastSnapshot = null
    }

    // --------------------------------------------------------------- rendering

    private fun setContent(views: List<View>) {
        val c = contentBox ?: return
        c.removeAllViews(); views.forEach { c.addView(it) }
    }

    private fun render(a: Analysis, generating: Boolean) {
        ensureRoot(); bubble?.alpha = 1f
        panel?.background = card(18, panelBg(), stroke = true) // re-apply in case opacity changed
        val views = ArrayList<View>()
        val group = lastSnapshot?.kind == ChatKind.GROUP

        if (group) {
            val title = GroupChat.displayTitle(lastSnapshot?.title)
            val who = lastSnapshot?.latestSpeaker
            val bits0 = ArrayList<String>()
            bits0.add("群聊 · $title")
            lastSnapshot?.memberCount?.let { bits0.add("${it}人") }
            if (lastSnapshot?.mentionedMe == true) bits0.add("@你")
            if (!who.isNullOrBlank()) bits0.add("最新 $who")
            views.add(line(bits0.joinToString("  ·  "), "#6B7280", 12f, true))
            lastSnapshot?.group?.digest?.takeIf { it.isNotBlank() }?.let {
                views.add(hint(it))
            }
        }
        lastSnapshot?.let { views.addAll(transcriptViews(it, 3)) }

        // Danger badge — the alarm signal, up top and color-coded.
        a.dangerLevel?.let {
            val lvl = it.score.roundToInt()
            views.add(dangerBadge(lvl, it.maxLevel))
            tintBubbleDanger(it.score)
        }
        // Intent headline.
        a.trueIntent?.let {
            val label = if (group) (GROUP_INTENT[it.choice] ?: INTENT[it.choice] ?: it.choice)
            else (INTENT[it.choice] ?: it.choice)
            views.add(line(if (group) "群里在做什么：$label" else "对方真实意图：$label", "#111827", 15f, true))
            views.add(hint("把握 ${(it.confidence * 100).roundToInt()}%"))
        }
        if (group) {
            a.groupRegister?.let {
                views.add(hint("场合：${REGISTER[it.choice] ?: it.choice}"))
            }
            a.threadStatus?.let {
                views.add(hint("线程：${THREAD[it.choice] ?: it.choice}"))
            }
            a.addressedToMe?.let {
                views.add(line(if (it >= 0.5) "这句是在叫你" else "这句不是在叫你", "#374151", 13f))
            }
            a.openLoop?.let {
                if (it >= 0.5) views.add(line("还有没回的问题", "#D97706", 13f, true))
            }
            a.replyTarget?.let {
                val who = when (it.choice) {
                    "latest_speaker" -> lastSnapshot?.latestSpeaker?.let { n -> "该回 $n" }
                    "earlier_asker" -> lastSnapshot?.group?.openAskSpeaker?.let { n -> "该回 $n（前面的问）" }
                    "whole_group" -> "对全群说一句即可"
                    "nobody" -> "不用点名"
                    else -> null
                }
                if (who != null) views.add(line(who, "#3A7AFE", 13f, true))
            }
        }
        // Compact secondary line: needs · action · reply-now.
        val bits = ArrayList<String>()
        val needsMap = if (group) GROUP_NEEDS else NEEDS
        val actionMap = if (group) GROUP_ACTION else ACTION
        a.sheNeeds?.let { bits.add("要${(needsMap[it.choice] ?: it.choice)}") }
        a.bestAction?.let { bits.add(actionMap[it.choice] ?: it.choice) }
        a.shouldReplyNow?.let {
            bits.add(
                if (group) (if (it >= 0.5) "该回" else "先别回")
                else (if (it >= 0.5) "可给实质" else "先别给实质")
            )
        }
        if (bits.isNotEmpty()) views.add(line(bits.joinToString("  ·  "), "#374151", 13f))
        a.tensionResolved?.let { if (it >= 0.7) views.add(line("✓ 紧张已缓解", "#16A34A", 12f)) }

        views.add(divider())
        views.add(line("候选回复（Jev 排序）", "#9CA3AF", 12f))
        if (generating) {
            views.add(hint("生成中…"))
        } else {
            val fill = lastFill ?: {}
            a.rankedReplies.forEachIndexed { i, r ->
                views.add(replyCard(i + 1, r.text, (r.prob * 100).roundToInt()) { raw ->
                    fill(decorateFill(raw))
                })
            }
            if (a.rankedReplies.isEmpty()) views.add(hint("（未生成候选回复）"))
        }
        views.add(reAnalyzeBtn())
        views.add(markAsMeRow())

        setContent(views)
        if (!expanded) toggle()
    }

    private fun dangerBadge(lvl: Int, max: Int): View {
        val color = dangerColor(lvl)
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(6))
        }
        row.addView(TextView(ctx).apply {
            text = "危险 $lvl/$max"
            setTextColor(Color.WHITE); textSize = 13f; setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = card(20, color)
        })
        row.addView(TextView(ctx).apply {
            text = "  " + dangerWord(lvl); setTextColor(color); textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
        })
        return row
    }

    private fun replyCard(rank: Int, text: String, pct: Int, onFill: (String) -> Unit): View {
        val top = rank == 1
        val cardBg = if (top) Color.parseColor("#EAF1FF") else Color.parseColor("#F3F4F6")
        val c = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = card(12, cardBg)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        }
        c.addView(TextView(ctx).apply {
            this.text = "#$rank · ${pct}%"; setTextColor(Color.parseColor("#3A7AFE")); textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
        })
        c.addView(TextView(ctx).apply {
            this.text = text; setTextColor(Color.parseColor("#111827")); textSize = 14f
            setPadding(0, dp(3), 0, dp(7)); setLineSpacing(dp(2).toFloat(), 1f)
        })
        val btns = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        btns.addView(pill("复制", false) { copy(text) })
        // Fill, then collapse so the input box + keyboard are visible to review/send.
        btns.addView(pill("填入", true) { onFill(text); if (expanded) toggle() })
        c.addView(btns)
        return c
    }

    private fun pill(label: String, primary: Boolean, onClick: () -> Unit) = TextView(ctx).apply {
        text = label; textSize = 13f; gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(if (primary) Color.WHITE else Color.parseColor("#3A7AFE"))
        background = card(18, if (primary) Color.parseColor("#3A7AFE") else Color.parseColor("#FFFFFF"), stroke = !primary)
        setPadding(dp(18), dp(6), dp(18), dp(6))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = dp(8) }
        setOnClickListener { onClick() }
    }

    private fun decorateFill(text: String): String {
        val snap = lastSnapshot ?: return text
        if (snap.kind != ChatKind.GROUP || !prefs.groupAtOnFill) return text
        val a = lastJudgment
        return GroupChat.applyAt(text, snap, a?.replyTarget?.choice, a?.bestAction?.choice)
    }

    private fun reAnalyzeBtn() = TextView(ctx).apply {
        text = "重新分析"; textSize = 13f; gravity = Gravity.CENTER
        setTextColor(Color.parseColor("#6B7280"))
        setPadding(dp(10), dp(10), dp(10), dp(4))
        setOnClickListener { onManualAnalyze?.invoke() }
    }

    private fun transcriptViews(snapshot: ChatSnapshot, max: Int): List<View> {
        val lines = snapshot.messages.takeLast(max)
        if (lines.isEmpty()) return emptyList()
        return lines.map { m ->
            val who = when {
                m.side == "me" -> "我"
                else -> m.speaker?.takeIf { it.isNotBlank() } ?: "对方"
            }
            val raw = m.text.replace('\n', ' ')
            val t = if (raw.length > 26) raw.take(25) + "…" else raw
            val color = if (m.side == "me") "#059669" else "#111827"
            line("$who：$t", color, 12f)
        }
    }

    private fun markAsMeRow(): View = TextView(ctx).apply {
        text = "最新这条是我说的"
        textSize = 12f
        gravity = Gravity.CENTER
        setTextColor(Color.parseColor("#059669"))
        setPadding(dp(8), dp(6), dp(8), dp(4))
        setOnClickListener { onMarkAsMe?.invoke() }
    }

    private fun tintBubbleDanger(score: Double) {
        val color = dangerColor(score.roundToInt())
        dangerDot?.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL; setColor(color); setStroke(dp(2), Color.WHITE)
        }
    }

    // --------------------------------------------------------------- helpers

    private fun line(text: String, color: String, size: Float, bold: Boolean = false) =
        TextView(ctx).apply {
            this.text = text; setTextColor(Color.parseColor(color)); textSize = size
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(2), 0, dp(2))
        }

    private fun hint(text: String) = line(text, "#9CA3AF", 12f)

    private fun divider() = View(ctx).apply {
        setBackgroundColor(Color.parseColor("#1F000000"))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(8); bottomMargin = dp(4)
        }
    }

    private fun copy(text: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("jev_reply", text))
        toast("已复制")
    }

    private fun dangerColor(lvl: Int): Int = when {
        lvl >= 6 -> Color.parseColor("#DC2626")
        lvl >= 3 -> Color.parseColor("#D97706")
        else -> Color.parseColor("#16A34A")
    }

    private fun dangerWord(lvl: Int): String = when {
        lvl >= 8 -> "很危险"
        lvl >= 6 -> "偏危险"
        lvl >= 3 -> "留神"
        else -> "安全"
    }

    companion object {
        private val INTENT = mapOf(
            "confirm_you_care" to "确认你在不在乎", "vent_anger" to "在发泄情绪",
            "request_action" to "要你办事", "seek_explanation" to "要个解释",
            "casual_chat" to "随便聊聊", "close_topic" to "事情过去了")
        private val NEEDS = mapOf(
            "apology" to "道歉", "action" to "具体行动", "explanation" to "解释",
            "care" to "你的在乎", "nothing" to "（不用做什么）")
        private val ACTION = mapOf(
            "check_history" to "翻聊天记录", "apologize" to "先道歉", "give_commitment" to "给承诺",
            "explain" to "解释清楚", "acknowledge" to "接住情绪", "say_less" to "少说两句",
            "make_plan" to "定个安排")
        private val GROUP_INTENT = mapOf(
            "ask_you" to "在问你", "assign_task" to "在派活", "coordinate" to "在协调",
            "announce" to "在通知", "joke" to "闲聊/玩笑", "call_out" to "当众点你",
            "off_topic" to "与你无关")
        private val GROUP_NEEDS = mapOf(
            "apology" to "你表态道歉", "action" to "你办事", "explanation" to "你解释",
            "care" to "表态/在场", "nothing" to "不用你回")
        private val GROUP_ACTION = mapOf(
            "reply_brief" to "简短回一句", "give_fact" to "给具体信息", "volunteer" to "接下任务",
            "wait" to "先别回", "clarify" to "先问清楚", "correct" to "礼貌纠正",
            "deescalate" to "降温")
        private val REGISTER = mapOf(
            "work" to "工作", "family" to "家人", "friends" to "朋友", "mixed" to "混合")
        private val THREAD = mapOf(
            "new_topic" to "换话题了", "continue" to "还在同一件事",
            "pile_on" to "几个人叠在一起", "resolved" to "已经收住")
    }
}
