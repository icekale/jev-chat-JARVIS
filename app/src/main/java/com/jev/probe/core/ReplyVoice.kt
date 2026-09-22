package com.jev.probe.core

/** Draft instructions: copy the user's WeChat mouth, then apply the relationship. */
object ReplyVoice {
    fun mine(messages: List<Msg>, limit: Int = 4): List<String> =
        messages.filter { it.side == "me" && it.text.isNotBlank() }
            .takeLast(limit)
            .map { it.text.trim().replace('\n', ' ') }

    fun system(group: Boolean): String = if (group) GROUP else DM

    fun voiceBlock(samples: List<String>): String =
        if (samples.isEmpty()) {
            "我在这段里还没说过话。先用短口语，别编客服或咨询师的人设。"
        } else {
            "我最近自己是这样发的。新的三句要像同一个人打的，长短和标点跟着这些：\n" +
                samples.joinToString("\n") { "· $it" }
        }

    fun userPrompt(
        group: Boolean,
        relationshipVoice: String,
        samples: List<String>,
        steer: String?,
        scene: String,
        transcript: String
    ): String {
        val kind = if (group) "群聊" else "私聊"
        val tone = if (steer.isNullOrBlank()) "" else "这句该做到的事（口气仍用我的，不要改成讲道理）：$steer\n\n"
        return "场景：$kind $scene\n\n" +
            "关系（只管口气，不许编对话里没有的事实）：\n$relationshipVoice\n\n" +
            voiceBlock(samples) + "\n\n" +
            tone +
            "最近对话：\n$transcript\n\n" +
            "只输出 3 条我接下来会发出去的话。"
    }

    private const val DM =
        "你在替我打微信，不是在写回复建议。只输出一个 JSON 数组，含且仅含 3 条字符串。" +
            "每条都像我刚打完就发出去：短，口语，可以不完整，常常没有句号。三条只是说法不同，不是三种人设。" +
            "对照：差「我理解你的感受，以后我会注意的。」好「我记着了。」" +
            "差「当然可以，我们找个时间好好沟通一下。」好「今晚说？」" +
            "差「好的呢，没问题，你放心。」好「行。」" +
            "禁止出现：我理解、你说得对、确实、当然可以、没问题、我会注意、希望你能理解、抱歉让你、首先、其次、作为你的、亲爱的。" +
            "不要解释，直接输出 JSON 数组。"

    private const val GROUP =
        "你在替我回微信群里的一句，不是写通知。只输出一个 JSON 数组，含且仅含 3 条字符串。" +
            "一句把事说完。不要用问候开头，不要自己加 @，不要情侣腔。" +
            "对照：差「收到，我会尽快处理并同步你。」好「我改完发你。」" +
            "差「好的呢，大家放心。」好「行，我来。」" +
            "禁止出现：我理解、当然可以、没问题、我会注意、希望你能理解。" +
            "不要解释，直接输出 JSON 数组。"
}
