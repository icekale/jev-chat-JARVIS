package com.jev.probe

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.jev.probe.BuildConfig
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.GroupAuto
import com.jev.probe.core.Msg
import com.jev.probe.core.Prefs
import com.jev.probe.jev.JevClient
import com.jev.probe.jev.JevProvider
import java.util.LinkedHashMap
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private val accent = Color.parseColor("#3A7AFE")
    private val ink = Color.parseColor("#111827")
    private val sub = Color.parseColor("#6B7280")

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        window.decorView.setBackgroundColor(Color.parseColor("#F2F3F5"))

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(28))
        }
        scroll.addView(root)

        root.addView(header("设置"))

        // --- 接口 ---
        root.addView(section("接口"))
        val card1 = card()
        card1.addView(label("Jev 判断接口"))
        var selectedProvider = prefs.jevProvider
        val hint = text("", 12f, sub).apply { setPadding(0, dp(6), 0, dp(2)) }
        val keyLabel = label("判断密钥")
        val keyEdit = edit(prefs.openRouterKey, keyHint(selectedProvider), password = true)
        val replyKeyLabel = label("回复生成密钥（可选）")
        val replyKeyEdit = edit(prefs.replyKey, "sk-... / sk-or-v1-...，自定义接口必填", password = true)
        fun refreshProviderUi() {
            hint.text = providerHint(selectedProvider)
            keyLabel.text = if (selectedProvider == JevProvider.TYPESAFE) "TypeSafe 密钥" else "OpenRouter 密钥"
            keyEdit.hint = keyHint(selectedProvider)
        }
        refreshProviderUi()
        card1.addView(providerRow(selectedProvider) { next ->
            selectedProvider = next
            refreshProviderUi()
        })
        card1.addView(hint)
        card1.addView(keyLabel)
        card1.addView(keyEdit)
        card1.addView(label("回复接口 Base URL（OpenAI 兼容）"))
        val replyBaseEdit = edit(prefs.replyBaseUrl, Prefs.DEFAULT_REPLY_BASE)
        card1.addView(replyBaseEdit)
        card1.addView(text("填到 /v1 即可，例如 https://api.openai.com/v1 或自建/中转。", 12f, sub))
        card1.addView(replyKeyLabel)
        card1.addView(replyKeyEdit)
        card1.addView(label("回复生成模型"))
        val modelEdit = edit(prefs.replyModel, Prefs.DEFAULT_REPLY_MODEL)
        card1.addView(modelEdit)
        root.addView(card1)

        // --- 分析 ---
        root.addView(section("分析"))
        val card2 = card()
        card2.addView(label("关系描述（给 Jev 判断用）"))
        val relEdit = edit(prefs.relationship, Prefs.DEFAULT_REL)
        card2.addView(relEdit)
        card2.addView(label("会话白名单（每行一个关键词，空=所有会话）"))
        val wlEdit = edit(prefs.whitelist.joinToString("\n"), "留空则对所有会话生效").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE; minLines = 2
        }
        card2.addView(wlEdit)
        card2.addView(text(
            "微信会自动跳过：朋友圈、聊天列表、发现/通讯录、视频号、小程序、文件传输助手、公众号和微信支付等官方号。",
            12f, sub
        ))
        val autoRow = toggleRow("私聊对方发消息时自动分析", prefs.autoAnalyze)
        card2.addView(autoRow)
        card2.addView(label("群聊自动分析"))
        var selectedGroupAuto = prefs.groupAuto
        card2.addView(groupAutoRow(selectedGroupAuto) { selectedGroupAuto = it })
        card2.addView(text("「与我相关」= @你 / 叫你办事 / 命中关注词 / 同一个人还在追问未回的事。", 12f, sub))
        card2.addView(label("我在群里的昵称（每行一个，用来识别 @你）"))
        val nickEdit = edit(prefs.myNicknames.joinToString("\n"), "例如微信显示名，可多行").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE; minLines = 2
        }
        card2.addView(nickEdit)
        card2.addView(label("群聊关注词（每行一个，命中也算与我相关）"))
        val watchEdit = edit(prefs.groupWatch.joinToString("\n"), "例如：你负责、截止日期、周报").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE; minLines = 2
        }
        card2.addView(watchEdit)
        val digestRow = toggleRow("群里未被点名时也显示摘要", prefs.groupDigest)
        card2.addView(digestRow)
        val atFillRow = toggleRow("填入时自动 @ 该回的人", prefs.groupAtOnFill)
        card2.addView(atFillRow)
        card2.addView(label("群聊关系描述（给 Jev 判断用）"))
        val groupRelEdit = edit(prefs.groupRelationship, Prefs.DEFAULT_GROUP_REL)
        card2.addView(groupRelEdit)
        if (prefs.myBubbleColor != 0) {
            card2.addView(text("已记住自己的气泡颜色（悬浮窗「最新这条是我说的」）。", 12f, sub))
            card2.addView(secondaryBtn("清除记住的气泡颜色") {
                prefs.myBubbleColor = 0
                Toast.makeText(this, "已清除", Toast.LENGTH_SHORT).show()
                recreate()
            })
        }
        root.addView(card2)

        // --- 外观 ---
        root.addView(section("外观"))
        val card3 = card()
        val opacityLabel = label("悬浮窗不透明度：${prefs.overlayOpacity}%")
        card3.addView(opacityLabel)
        card3.addView(text("越低越透，越能看清下面的聊天", 12f, sub))
        val seek = SeekBar(this).apply {
            max = 40; progress = prefs.overlayOpacity - 60  // 60..100
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) {
                    opacityLabel.text = "悬浮窗不透明度：${p + 60}%"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        card3.addView(seek)
        root.addView(card3)

        // --- Actions ---
        val result = text("", 13f, sub).apply { setPadding(0, dp(12), 0, dp(4)) }
        root.addView(primaryBtn("保存") {
            prefs.jevProvider = selectedProvider
            prefs.openRouterKey = keyEdit.text.toString()
            prefs.replyKey = replyKeyEdit.text.toString()
            prefs.replyBaseUrl = replyBaseEdit.text.toString()
            prefs.replyModel = modelEdit.text.toString().ifBlank { Prefs.DEFAULT_REPLY_MODEL }
            prefs.relationship = relEdit.text.toString().ifBlank { Prefs.DEFAULT_REL }
            prefs.whitelist = wlEdit.text.toString().split("\n").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            prefs.autoAnalyze = (autoRow.tag as? Boolean) ?: true
            prefs.groupAuto = selectedGroupAuto
            prefs.myNicknames = nickEdit.text.toString().split("\n").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            prefs.groupWatch = watchEdit.text.toString().split("\n").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            prefs.groupDigest = (digestRow.tag as? Boolean) ?: true
            prefs.groupAtOnFill = (atFillRow.tag as? Boolean) ?: true
            prefs.groupRelationship = groupRelEdit.text.toString().ifBlank { Prefs.DEFAULT_GROUP_REL }
            prefs.overlayOpacity = seek.progress + 60
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
        })
        root.addView(secondaryBtn("连通测试") {
            val key = keyEdit.text.toString().trim()
            val replyKey = replyKeyEdit.text.toString().trim()
            val replyBase = replyBaseEdit.text.toString().trim()
            val model = modelEdit.text.toString().trim().ifBlank { Prefs.DEFAULT_REPLY_MODEL }
            if (key.isBlank()) { result.text = "请先填判断密钥"; return@secondaryBtn }
            result.text = "测试中…"
            worker.execute {
                val demo = ChatSnapshot("连通测试", listOf(
                    Msg("other", "在吗？"), Msg("me", "在"), Msg("other", "那你说说昨天答应我的事")))
                val a = JevClient(key, model, selectedProvider, replyKey, replyBase)
                    .analyze(demo, relEdit.text.toString().ifBlank { Prefs.DEFAULT_REL })
                main.post {
                    result.text = if (a.error != null) "失败：${a.error}"
                    else {
                        val canReuseOrKey = replyKey.isNotBlank() ||
                            (Prefs.isOpenRouterChat(replyBase) && selectedProvider == JevProvider.OPENROUTER)
                        val draftNote = if (a.rankedReplies.isEmpty() && !canReuseOrKey)
                            "（未填回复密钥，已跳过候选起草）" else ""
                        "成功（${selectedProvider.displayName}）：意图=${a.trueIntent?.choice ?: "?"}，" +
                            "候选=${a.rankedReplies.size} 条，耗时 ${a.latencyMs}ms$draftNote"
                    }
                }
            }
        })
        root.addView(result)
        root.addView(text(
            "版本 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            12f, sub
        ).apply { setPadding(0, dp(18), 0, 0) })

        setContentView(scroll)
    }

    private fun keyHint(provider: JevProvider) = when (provider) {
        JevProvider.OPENROUTER -> "sk-or-v1-..."
        JevProvider.TYPESAFE -> "官网控制台的 TYPESAFE_API_KEY"
    }

    private fun providerHint(provider: JevProvider) = when (provider) {
        JevProvider.OPENROUTER ->
            "判断走 OpenRouter /api/alpha/decisions（typesafe/jev-1.13）。回复默认也走 OpenRouter，可改成任意 OpenAI 兼容接口。"
        JevProvider.TYPESAFE ->
            "判断走官网 POST https://api.typesafe.ai/v1/systemone（jev-latest）。Jev 不生成文字；候选回复走下方 OpenAI 兼容接口，需另填回复密钥。"
    }

    private fun providerRow(initial: JevProvider, onChange: (JevProvider) -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(2))
        }
        val buttons = LinkedHashMap<JevProvider, TextView>()
        fun paint() {
            buttons.forEach { (p, tv) ->
                val on = p == (row.tag as? JevProvider ?: initial)
                tv.setTextColor(if (on) Color.WHITE else sub)
                tv.background = round(dp(10), if (on) accent else Color.parseColor("#E5E7EB"))
            }
        }
        JevProvider.entries.forEach { p ->
            val tv = TextView(this).apply {
                text = p.displayName; textSize = 13f; gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(14), dp(8), dp(14), dp(8))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { if (p != JevProvider.OPENROUTER) leftMargin = dp(8) }
                setOnClickListener {
                    row.tag = p
                    paint()
                    onChange(p)
                }
            }
            buttons[p] = tv
            row.addView(tv)
        }
        row.tag = initial
        paint()
        return row
    }

    private fun groupAutoRow(initial: GroupAuto, onChange: (GroupAuto) -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(2))
        }
        val buttons = LinkedHashMap<GroupAuto, TextView>()
        fun paint() {
            buttons.forEach { (p, tv) ->
                val on = p == (row.tag as? GroupAuto ?: initial)
                tv.setTextColor(if (on) Color.WHITE else sub)
                tv.background = round(dp(10), if (on) accent else Color.parseColor("#E5E7EB"))
            }
        }
        GroupAuto.entries.forEach { p ->
            val tv = TextView(this).apply {
                text = p.label; textSize = 13f; gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { if (p != GroupAuto.MENTION) leftMargin = dp(8) }
                setOnClickListener {
                    row.tag = p
                    paint()
                    onChange(p)
                }
            }
            buttons[p] = tv
            row.addView(tv)
        }
        row.tag = initial
        paint()
        return row
    }

    private fun toggleRow(labelText: String, initial: Boolean): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(2)); tag = initial
        }
        val lab = text(labelText, 14f, ink).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val sw = TextView(this).apply {
            text = if (initial) "开" else "关"; textSize = 13f; gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (initial) Color.WHITE else sub)
            background = round(dp(10), if (initial) accent else Color.parseColor("#E5E7EB"))
            setPadding(dp(18), dp(6), dp(18), dp(6))
        }
        sw.setOnClickListener {
            val now = !((row.tag as? Boolean) ?: true); row.tag = now
            sw.text = if (now) "开" else "关"
            sw.setTextColor(if (now) Color.WHITE else sub)
            sw.background = round(dp(10), if (now) accent else Color.parseColor("#E5E7EB"))
        }
        row.addView(lab); row.addView(sw)
        return row
    }

    // atoms
    private fun header(t: String) = text(t, 24f, ink, bold = true).apply { setPadding(0, 0, 0, dp(4)) }
    private fun section(t: String) = text(t, 12f, sub, bold = true).apply { setPadding(dp(2), dp(16), 0, dp(6)) }
    private fun label(t: String) = text(t, 13f, ink, bold = true).apply { setPadding(0, dp(12), 0, dp(4)) }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = round(dp(14), Color.WHITE)
        setPadding(dp(14), dp(4), dp(14), dp(14))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun edit(value: String, hint: String, password: Boolean = false) = EditText(this).apply {
        setText(value); this.hint = hint; textSize = 14f; setTextColor(ink)
        background = round(dp(8), Color.parseColor("#F3F4F6"))
        setPadding(dp(10), dp(10), dp(10), dp(10))
        if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) }
    }

    private fun text(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun primaryBtn(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 15f; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.WHITE); background = round(dp(12), accent)
        setPadding(dp(16), dp(13), dp(16), dp(13))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) }
        setOnClickListener { onClick() }
    }

    private fun secondaryBtn(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 15f; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
        setTextColor(accent); background = round(dp(12), Color.WHITE, stroke = true)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
        setOnClickListener { onClick() }
    }

    private fun round(radius: Int, color: Int, stroke: Boolean = false) = GradientDrawable().apply {
        cornerRadius = radius.toFloat(); setColor(color); if (stroke) setStroke(dp(1), accent)
    }

    override fun onDestroy() { super.onDestroy(); worker.shutdownNow() }
}
