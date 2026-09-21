package com.jev.probe.core

enum class ChatKind { DM, GROUP }

enum class GroupAuto(val id: String, val label: String) {
    MENTION("mention", "与我相关"),
    ALL("all", "每条"),
    OFF("off", "只手动");

    companion object {
        fun from(raw: String?): GroupAuto = entries.find { it.id == raw } ?: MENTION
    }
}

/** Local read of a group thread: who spoke, who was @'d, open asks, money skip. */
data class GroupContext(
    val speakers: List<String>,
    val atTargets: List<String>,
    val mentionedMe: Boolean,
    val mentionedEarlier: Boolean,
    val directedAtMe: Boolean,
    val watchHits: List<String>,
    val openAsk: String?,
    val openAskSpeaker: String?,
    val moneyRelated: Boolean,
    val relevantNow: Boolean,
    val digest: String
)

/** Pure group-chat detection, @ matching, and auto-analyze policy. */
object GroupChat {
    private val TITLE_REN = Regex("""[（(]\s*(\d+)\s*人\s*[)）]\s*$""")
    private val TITLE_COUNT = Regex("""[（(]\s*(\d+)\s*[)）]\s*$""")
    private val AT = Regex("""@\s*([^\s@]{1,16})""")
    private val DIRECTED = Regex(
        """你(来|看|负责|定|回|说|处理)|帮我|麻烦你|等你|交给你|你来一下|你看一下"""
    )
    private val FOLLOW = Regex("""^(呢|在吗|回一下|看到了吗|怎么样|如何)\s*[?？!！]*$""")
    private val MONEY = listOf("红包", "转账", "收款码", "收钱", "微信红包", "向你付款", "已收款")
    private val SYSTEM = listOf("加入了群聊", "退出了群聊", "撤回了一条", "拍了拍", "邀请你")

    fun memberCount(title: String?): Int? {
        if (title.isNullOrBlank()) return null
        TITLE_REN.find(title)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        TITLE_COUNT.find(title)?.groupValues?.get(1)?.toIntOrNull()?.let { n ->
            if (n >= 3) return n
        }
        return null
    }

    fun displayTitle(title: String?): String {
        if (title.isNullOrBlank()) return "群聊"
        return title.replace(TITLE_REN, "").replace(TITLE_COUNT, "").trim().ifBlank { title }
    }

    fun isGroup(title: String?, otherSpeakers: Collection<String>): Boolean {
        if (memberCount(title) != null) return true
        if (title?.contains("群聊") == true) return true
        val named = otherSpeakers.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (named.size >= 2) return true
        if (title?.contains("群") == true && named.isNotEmpty()) return true
        return false
    }

    fun mentionIn(text: String, myNames: Set<String>): Boolean {
        if (text.contains("@所有人") || text.contains("@全体成员") || text.contains("@我"))
            return true
        if (Regex("""(?i)@all\b""").containsMatchIn(text)) return true
        val targets = atTargets(text)
        if (targets.any { it == "所有人" || it == "全体成员" || it.equals("all", true) || it == "我" })
            return true
        val mine = myNames.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        return targets.any { t -> mine.any { it.equals(t, ignoreCase = true) || t.contains(it) } }
    }

    fun atTargets(text: String): List<String> =
        AT.findAll(text).map { it.groupValues[1].trim().trimEnd('，', ',', '。', '!') }
            .filter { it.isNotEmpty() }.distinct().toList()

    fun directedAtMe(text: String): Boolean = DIRECTED.containsMatchIn(text)

    fun looksLikeMoney(text: String): Boolean = MONEY.any { text.contains(it) }

    fun looksLikeSystem(text: String): Boolean = SYSTEM.any { text.contains(it) }

    fun mentionedMe(messages: List<Msg>, myNames: Set<String>): Boolean {
        val latest = latestOther(messages) ?: return false
        return mentionIn(latest.text, myNames)
    }

    fun context(messages: List<Msg>, myNames: Set<String>, watchWords: Set<String>): GroupContext {
        val last10 = messages.takeLast(32)
        val speakers = last10.mapNotNull { it.speaker?.trim() }.filter { it.isNotEmpty() }.distinct()
        val ats = last10.flatMap { atTargets(it.text) }.distinct()
        val latest = latestOther(last10)
        val mentionedNow = latest != null && mentionIn(latest.text, myNames)
        val mentionedEarlier = last10.any { it.side == "other" && mentionIn(it.text, myNames) }
        val directedNow = latest != null && directedAtMe(latest.text)
        val watch = watchHits(latest?.text.orEmpty(), watchWords)
        val money = latest != null && looksLikeMoney(latest.text)
        val (openAsk, openWho) = openAsk(last10, myNames)
        val continues = latest != null && openWho != null && (
            latest.speaker == openWho || FOLLOW.containsMatchIn(latest.text.trim())
        )
        val relevant = !money && (
            mentionedNow || directedNow || watch.isNotEmpty() || continues
        )
        val digest = digestLine(
            speakers, mentionedNow, mentionedEarlier, openAsk, watch, money, latest
        )
        return GroupContext(
            speakers = speakers,
            atTargets = ats,
            mentionedMe = mentionedNow,
            mentionedEarlier = mentionedEarlier && !mentionedNow,
            directedAtMe = directedNow,
            watchHits = watch,
            openAsk = openAsk,
            openAskSpeaker = openWho,
            moneyRelated = money,
            relevantNow = relevant,
            digest = digest
        )
    }

    fun shouldAutoAnalyze(snapshot: ChatSnapshot, autoOn: Boolean, groupAuto: GroupAuto): Boolean {
        if (!autoOn) return false
        if (snapshot.latestFrom != "other") return false
        if (snapshot.kind != ChatKind.GROUP) return true
        val g = snapshot.group
        if (g?.moneyRelated == true) return false
        return when (groupAuto) {
            GroupAuto.ALL -> true
            GroupAuto.OFF -> false
            GroupAuto.MENTION -> g?.relevantNow == true || snapshot.mentionedMe
        }
    }

    fun applyAt(text: String, snapshot: ChatSnapshot, target: String?, action: String?): String {
        if (text.startsWith("@")) return text
        if (action == "wait") return text
        val name = when (target) {
            "latest_speaker" -> snapshot.latestSpeaker
            "earlier_asker" -> snapshot.group?.openAskSpeaker ?: snapshot.latestSpeaker
            else -> null
        }?.trim().orEmpty()
        if (name.isEmpty() || name == "我") return text
        return "@$name $text"
    }

    private fun latestOther(messages: List<Msg>): Msg? =
        messages.lastOrNull { it.side == "other" && !looksLikeSystem(it.text) }

    private fun watchHits(text: String, words: Set<String>): List<String> =
        words.map { it.trim() }.filter { it.isNotEmpty() && text.contains(it) }.distinct()

    private fun openAsk(messages: List<Msg>, myNames: Set<String>): Pair<String?, String?> {
        var pending: Msg? = null
        for (m in messages) {
            if (m.side == "me") {
                pending = null
                continue
            }
            if (looksLikeSystem(m.text) || looksLikeMoney(m.text)) continue
            if (mentionIn(m.text, myNames) || directedAtMe(m.text)) pending = m
        }
        val last = latestOther(messages)
        if (pending != null && last != null && pending.text == last.text && pending.speaker == last.speaker) {
            // still the latest line; keep it
        }
        return pending?.text to pending?.speaker
    }

    private fun digestLine(
        speakers: List<String>,
        mentionedNow: Boolean,
        mentionedEarlier: Boolean,
        openAsk: String?,
        watch: List<String>,
        money: Boolean,
        latest: Msg?
    ): String {
        val bits = ArrayList<String>()
        if (speakers.isNotEmpty()) bits.add("${speakers.size}人在说话")
        when {
            mentionedNow -> bits.add("@你")
            mentionedEarlier -> bits.add("前面有人@你")
        }
        if (watch.isNotEmpty()) bits.add("关键词「${watch.first()}」")
        if (money) bits.add("涉及收付款（已跳过）")
        openAsk?.let { bits.add("未回：${it.take(18)}") }
        if (bits.isEmpty()) {
            val who = latest?.speaker
            val t = latest?.text?.take(16)
            return if (!who.isNullOrBlank() && !t.isNullOrBlank()) "$who：$t"
            else "群聊 · 点此分析"
        }
        return bits.joinToString(" · ")
    }
}
