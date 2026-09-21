package com.jev.probe.capture

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.jev.probe.core.ChatKind
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.GroupChat
import com.jev.probe.core.Prefs
import com.jev.probe.jev.JevClient
import com.jev.probe.overlay.OverlayController
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Live capture service. Reads whichever adapted chat app is in the foreground,
 * detects a new incoming message, runs Jev off the main thread, and drives the
 * overlay.
 *
 * It never sends a message. The only write is ACTION_SET_TEXT / PASTE into the
 * input box when the user taps "填入"; the user still presses send.
 */
open class ChatCaptureService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newFixedThreadPool(2)
    private val adapters = listOf(WeChatAdapter(), FeishuAdapter()).associateBy { it.pkg }

    private fun submit(task: () -> Unit) {
        try { worker.execute(task) } catch (_: RejectedExecutionException) { }
    }

    private var prefs: Prefs? = null
    private var overlay: OverlayController? = null

    private var lastSignature: String = ""
    private var activePkg: String? = null
    private var pendingSnapshot: ChatSnapshot? = null
    @Volatile private var currentSnapshot: ChatSnapshot? = null
    @Volatile private var generation = 0L
    private var lastWalkAt = 0L

    private val debounce = Runnable { runAnalysis() }
    private val captureSoon = Runnable { maybeCapture() }
    private val heartbeat = object : Runnable {
        override fun run() {
            hideIfLeft()
            main.postDelayed(this, 1500)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (prefs == null) prefs = Prefs(this)
        overlay = OverlayController(this)
        overlay?.onManualAnalyze = {
            currentSnapshot?.let { pendingSnapshot = it; runAnalysis() }
        }
        runCatching { KeepAliveService.start(this) }
        main.removeCallbacks(heartbeat)
        main.post(heartbeat)
        main.postDelayed({ if (prefs?.enabled == true) runCatching { maybeCapture() } }, 900)
        Log.i(TAG, "capture service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val p = prefs ?: return
        if (event == null) return
        if (!p.enabled) { main.post { overlay?.hide() }; return }

        val type = event.eventType
        val root = rootInActiveWindow
        val rootPkg = root?.packageName?.toString()
        if (rootPkg != null && rootPkg !in adapters) {
            recycleQuiet(root)
            main.post { overlay?.hide() }
            return
        }

        when (type) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> scheduleCapture()
        }
    }

    private fun scheduleCapture() {
        val now = System.currentTimeMillis()
        val wait = (THROTTLE_MS - (now - lastWalkAt)).coerceAtLeast(0L)
        main.removeCallbacks(captureSoon)
        main.postDelayed(captureSoon, wait)
    }

    private fun hideIfLeft() {
        val root = rootInActiveWindow
        val pkg = root?.packageName?.toString()
        recycleQuiet(root)
        if (pkg !in adapters && overlay?.isShowing() == true) overlay?.hide()
    }

    private fun maybeCapture() {
        lastWalkAt = System.currentTimeMillis()
        val p = prefs ?: return
        val root = rootInActiveWindow ?: return
        try {
            val pkg = root.packageName?.toString()
            val adapter = adapters[pkg]
            if (adapter == null) {
                overlay?.hide()
                return
            }
            val raw = adapter.extract(root, resources) ?: return
            if (raw.messages.isEmpty()) return
            if (!p.isAllowed(raw.title)) { overlay?.hide(); return }

            val speakers = raw.messages.mapNotNull { it.speaker }.filter { it.isNotBlank() }.toSet()
            val kind = if (raw.kind == ChatKind.GROUP || GroupChat.isGroup(raw.title, speakers))
                ChatKind.GROUP else ChatKind.DM
            val ctx = if (kind == ChatKind.GROUP)
                GroupChat.context(raw.messages, p.myNicknames, p.groupWatch) else null
            val snapshot = raw.copy(
                kind = kind,
                mentionedMe = ctx?.mentionedMe ?: false,
                memberCount = raw.memberCount ?: GroupChat.memberCount(raw.title),
                group = ctx
            )

            if (pkg != activePkg) { activePkg = pkg; lastSignature = "" }

            currentSnapshot = snapshot
            val sig = snapshot.signature()
            val showing = overlay?.isShowing() == true
            if (sig == lastSignature && showing) return
            if (sig == lastSignature && !showing) {
                overlay?.showIdle(snapshot)
                return
            }
            lastSignature = sig

            if (snapshot.group?.moneyRelated == true) {
                overlay?.showIdle(snapshot)
                return
            }

            if (!GroupChat.shouldAutoAnalyze(snapshot, p.autoAnalyze, p.groupAuto)) {
                overlay?.showIdle(snapshot)
                return
            }

            pendingSnapshot = snapshot
            main.removeCallbacks(debounce)
            main.postDelayed(debounce, 800)
        } finally {
            recycleQuiet(root)
        }
    }

    private fun runAnalysis() {
        val snapshot = pendingSnapshot ?: return
        val p = prefs ?: return
        if (snapshot.group?.moneyRelated == true) {
            overlay?.showIdle(snapshot)
            return
        }
        if (!p.hasKey()) { overlay?.showError("未设置接口密钥，去设置里填"); return }
        val gen = ++generation
        val sig = snapshot.signature()
        overlay?.bindSnapshot(snapshot)
        overlay?.showLoading()
        val client = JevClient(
            p.openRouterKey, p.replyModel, p.jevProvider,
            p.replyKey, p.replyBaseUrl
        )
        val rel = if (snapshot.kind == ChatKind.GROUP) p.groupRelationship else p.relationship
        submit {
            val judgment = client.judge(snapshot, rel)
            main.post {
                if (stale(gen, sig)) return@post
                if (judgment.error != null) {
                    generation++
                    overlay?.showError(judgment.error)
                } else overlay?.showJudgment(judgment)
            }
            if (judgment.error != null) return@submit
            if (stale(gen, sig)) return@submit
            val ranked = try { client.draftAndRank(snapshot, rel) } catch (_: Exception) { emptyList() }
            main.post {
                if (stale(gen, sig)) return@post
                overlay?.showReplies(ranked) { text -> fillInput(text) }
            }
        }
    }

    private fun stale(gen: Long, sig: String): Boolean =
        gen != generation || currentSnapshot?.signature() != sig

    private fun fillInput(text: String) {
        submit {
            var ok = trySetText(text)
            if (!ok) {
                val edit = takeEditable()
                if (edit != null) {
                    try {
                        edit.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        Thread.sleep(300)
                        ok = trySetText(text)
                        if (!ok) {
                            copyToClipboard(text)
                            val focused = takeEditable() ?: edit
                            try {
                                setTextRaw(focused, "")
                                val pasted = focused.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                                Thread.sleep(150)
                                val after = readInput()
                                ok = (after != null && after.contains(text)) || (pasted && after == null)
                            } finally {
                                if (focused !== edit) recycleQuiet(focused)
                            }
                        }
                    } finally {
                        recycleQuiet(edit)
                    }
                }
            }
            main.post {
                if (ok) overlay?.toast("已填入，确认后自己发送")
                else { copyToClipboard(text); overlay?.toast("已复制，长按输入框粘贴") }
            }
        }
    }

    private fun trySetText(text: String): Boolean {
        val edit = takeEditable() ?: return false
        return try {
            if (!setTextRaw(edit, text)) return false
            Thread.sleep(150)
            readInput() == text
        } finally {
            recycleQuiet(edit)
        }
    }

    private fun setTextRaw(edit: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun readInput(): String? {
        val edit = takeEditable() ?: return null
        return try {
            runCatching { edit.refresh() }
            edit.text?.toString()
        } finally {
            recycleQuiet(edit)
        }
    }

    private fun takeEditable(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val pkg = root.packageName?.toString()
        if (pkg !in adapters) {
            recycleQuiet(root)
            return null
        }
        var found: AccessibilityNodeInfo? = null
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var guard = 0
        while (stack.isNotEmpty() && guard < 4000) {
            guard++
            val node = stack.removeLast()
            if (found == null && node.isEditable) {
                found = node
            } else if (found == null) {
                for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it) }
            }
            if (node !== root && node !== found) recycleQuiet(node)
        }
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            if (n !== root && n !== found) recycleQuiet(n)
        }
        recycleQuiet(root)
        return found
    }

    private fun recycleQuiet(node: AccessibilityNodeInfo?) {
        if (node == null) return
        runCatching { node.recycle() }
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("jev_reply", text))
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacks(heartbeat)
        main.removeCallbacks(captureSoon)
        main.removeCallbacks(debounce)
        overlay?.onManualAnalyze = null
        overlay?.hide()
        overlay = null
        worker.shutdownNow()
    }

    companion object {
        private const val TAG = "JEVASSIST"
        private const val THROTTLE_MS = 300L
    }
}
