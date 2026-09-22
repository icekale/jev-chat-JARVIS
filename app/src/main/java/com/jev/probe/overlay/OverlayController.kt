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
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.jev.probe.core.Analysis
import com.jev.probe.core.ChatKind
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.GroupChat
import com.jev.probe.core.PanelCue
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
    private var panel: LinearLayout? = null
    private var contentBox: LinearLayout? = null
    private var expanded = false
    private var lp: WindowManager.LayoutParams? = null

    var onManualAnalyze: (() -> Unit)? = null
    var onNeedReplies: (() -> Unit)? = null
    var onMarkAsMe: (() -> Unit)? = null
    var chatKey: String? = null
    var priorAffect: String? = null

    /** Whether the overlay window is currently on screen. */
    fun isShowing(): Boolean = root?.isAttachedToWindow == true

    private var lastJudgment: Analysis? = null
    private var lastFill: ((String) -> Unit)? = null
    private var bubbleMenu: View? = null
    private var lastSnapshot: ChatSnapshot? = null
    private var editingRel = false
    private var busy = false

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
            bubbleMenu = null
            expanded = false
            editingRel = false
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
        wrap.addView(b)
        attachBubbleTouch(wrap, params)
        bubble = b
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
                    } else { onBubbleTap(); true }
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
            layoutParams = FrameLayout.LayoutParams(dp(188), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(56) }
        }
        menu.addView(menuItem("读到的记录") { dismissMenu(); showTranscript() })
        menu.addView(menuItem("这段关系") { dismissMenu(); openRelEditor() })
        menu.addView(menuItem("最新这条是我说的") { dismissMenu(); onMarkAsMe?.invoke() })
        menu.addView(menuItem("重新分析") { dismissMenu(); onManualAnalyze?.invoke() })
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
        val changed = snapshot.signature() != lastSnapshot?.signature()
        lastSnapshot = snapshot
        if (editingRel || busy) return
        ensureRoot()
        if (snapshot.kind == ChatKind.GROUP && changed) {
            lastJudgment = null
            if (expanded) toggle()
        }
        if (lastJudgment == null) paintBubble(null)
    }

    private fun onBubbleTap() {
        dismissMenu()
        if (editingRel) {
            finishRelEdit()
            return
        }
        if (busy) return
        if (expanded) {
            toggle()
            return
        }
        val a = lastJudgment
        when {
            a == null -> onManualAnalyze?.invoke()
            a.rankedReplies.isEmpty() -> onNeedReplies?.invoke()
            else -> toggle()
        }
    }

    fun bindSnapshot(snapshot: ChatSnapshot) {
        lastSnapshot = snapshot
    }

    /** Bubble only. The chat stays visible until replies are ready. */
    fun showBusy() {
        if (editingRel) {
            editingRel = false
            setOverlayFocusable(false)
        }
        busy = true
        ensureRoot()
        if (expanded) toggle()
        paintBubble(lastJudgment)
    }

    fun showError(msg: String) {
        busy = false
        ensureRoot()
        paintBubble(null)
        setContent(listOf(
            line("出错了", "#DC2626", 14f, true),
            hint(msg)))
        if (!expanded) toggle()
    }

    /** Judgment is in. [drafting] keeps the bubble spinning and the panel shut. */
    fun onJudged(a: Analysis, drafting: Boolean) {
        lastJudgment = a
        busy = drafting
        ensureRoot()
        paintBubble(a)
        if (expanded) toggle()
    }

    fun stopBusy() {
        busy = false
        paintBubble(lastJudgment)
    }

    fun showReplies(ranked: List<RankedReply>, onFill: (String) -> Unit) {
        busy = false
        lastFill = onFill
        val a = lastJudgment?.copy(rankedReplies = ranked) ?: return
        lastJudgment = a
        ensureRoot()
        paintBubble(a)
        renderReplyPanel(a)
    }

    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

    fun forgetJudgment() {
        lastJudgment = null
    }

    fun hide() {
        val r = root ?: return
        runCatching { wm.removeView(r) }
        root = null; bubble = null; panel = null; contentBox = null
        bubbleMenu = null; expanded = false
        editingRel = false
        busy = false
        lastJudgment = null; lastFill = null; lastSnapshot = null
    }

    // --------------------------------------------------------------- rendering

    private fun setContent(views: List<View>) {
        val c = contentBox ?: return
        c.removeAllViews(); views.forEach { c.addView(it) }
    }

    private fun renderReplyPanel(a: Analysis) {
        if (editingRel) return
        panel?.background = card(18, panelBg(), stroke = true)
        val group = lastSnapshot?.kind == ChatKind.GROUP
        val views = ArrayList<View>()
        views.add(line(PanelCue.summary(a, priorAffect, group), "#111827", 15f, true))
        views.add(hint("点一句填入，长按复制"))
        val fill = lastFill ?: {}
        if (a.rankedReplies.isEmpty()) {
            views.add(hint("（未生成候选回复）"))
        } else {
            a.rankedReplies.forEachIndexed { i, r ->
                views.add(replyCard(i == 0, r.text) { raw ->
                    fill(decorateFill(raw))
                    if (expanded) toggle()
                })
            }
        }
        setContent(views)
        if (!expanded) toggle()
    }

    private fun showTranscript() {
        val snap = lastSnapshot ?: return
        if (editingRel) return
        val views = ArrayList<View>()
        views.add(line("读到的", "#111827", 14f, true))
        val lines = transcriptViews(snap, 8)
        if (lines.isEmpty()) views.add(hint("还没读到消息")) else views.addAll(lines)
        setContent(views)
        if (!expanded) toggle()
    }

    private fun paintBubble(a: Analysis?) {
        val b = bubble ?: return
        val mentioned = lastSnapshot?.mentionedMe == true
        val ping = !mentioned && prefs.groupDigest && lastSnapshot?.group?.relevantNow == true
        b.text = if (busy) "…" else PanelCue.bubbleText(mentioned, ping, a, priorAffect)
        b.textSize = if ((b.text?.length ?: 0) > 2) 11f else 13f
        b.alpha = if (a == null && !busy) 0.55f else 1f
        val tone = PanelCue.tone(if (busy && a == null) null else a, priorAffect)
        val color = when (tone) {
            PanelCue.Tone.IDLE -> Color.argb(235, 58, 122, 254)
            PanelCue.Tone.SAFE -> Color.parseColor("#16A34A")
            PanelCue.Tone.WATCH -> Color.parseColor("#D97706")
            PanelCue.Tone.HOT -> Color.parseColor("#7C3AED")
            PanelCue.Tone.DANGER -> Color.parseColor("#DC2626")
        }
        b.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
    }

    private fun replyCard(top: Boolean, text: String, onFill: (String) -> Unit): View {
        val cardBg = if (top) Color.parseColor("#EAF1FF") else Color.parseColor("#F3F4F6")
        return TextView(ctx).apply {
            this.text = text
            setTextColor(Color.parseColor("#111827"))
            textSize = 15f
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setLineSpacing(dp(2).toFloat(), 1f)
            background = card(12, cardBg)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            setOnClickListener { onFill(text) }
            setOnLongClickListener { copy(text); true }
        }
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

    // --------------------------------------------------------------- helpers

    private fun line(text: String, color: String, size: Float, bold: Boolean = false) =
        TextView(ctx).apply {
            this.text = text; setTextColor(Color.parseColor(color)); textSize = size
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(2), 0, dp(2))
        }

    private fun hint(text: String) = line(text, "#9CA3AF", 12f)

    private fun copy(text: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("jev_reply", text))
        toast("已复制")
    }

    private fun openRelEditor() {
        val key = chatKey
        if (key.isNullOrBlank()) {
            toast("还没认出这个聊天")
            return
        }
        editingRel = true
        if (!expanded) toggle()
        val group = lastSnapshot?.kind == ChatKind.GROUP
        val edit = EditText(ctx).apply {
            setText(prefs.relationshipFor(key, group))
            setSelection(text.length)
            hint = "只对这个聊天。留空恢复默认"
            setHintTextColor(Color.parseColor("#9CA3AF"))
            setTextColor(Color.parseColor("#111827"))
            textSize = 13f
            maxLines = 4
            background = card(10, Color.parseColor("#F3F4F6"))
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        row.addView(pill("记住", true) {
            prefs.setChatRelationship(key, edit.text?.toString().orEmpty())
            hideIme(edit)
            val blank = edit.text.isNullOrBlank()
            finishRelEdit()
            toast(if (blank) "已恢复默认关系" else "已记住这段关系")
        })
        row.addView(pill("取消", false) {
            hideIme(edit)
            finishRelEdit()
        })
        setContent(listOf(
            line("这段聊天的关系", "#111827", 14f, true),
            hint("不改设置里的默认。留空就恢复默认。"),
            edit,
            row
        ))
        setOverlayFocusable(true)
        edit.requestFocus()
        edit.post {
            val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun finishRelEdit() {
        editingRel = false
        setOverlayFocusable(false)
        val a = lastJudgment
        if (a != null && a.rankedReplies.isNotEmpty()) renderReplyPanel(a)
        else if (expanded) toggle()
    }

    private fun hideIme(v: View) {
        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(v.windowToken, 0)
    }

    private fun setOverlayFocusable(focusable: Boolean) {
        val params = lp ?: return
        val r = root ?: return
        params.flags = if (focusable) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        runCatching { wm.updateViewLayout(r, params) }
    }
}
