package com.jev.probe.core

/** Draft instructions that copy the user's own WeChat voice instead of an assistant. */
object ReplyVoice {
    fun mine(messages: List<Msg>, limit: Int = 4): List<String> =
        messages.filter { it.side == "me" && it.text.isNotBlank() }
            .takeLast(limit)
            .map { it.text.trim().replace('\n', ' ') }

    fun system(group: Boolean): String = if (group) GROUP else DM

    fun voiceBlock(samples: List<String>): String =
        if (samples.isEmpty()) {
            "我在这段里还没说过话。用短口语，别编一种客服或咨询师的人设。"
        } else {
            "我最近自己是这样发的，新的三句要像同一个人打出来的：\n" +
                samples.joinToString("\n") { "· $it" }
        }

    private const val DM =
        "你在替我回微信私聊，不是客服，不是情感咨询师。只输出一个 JSON 数组，含且仅含 3 条我真的会发出去的话。" +
            "模仿我已经发过的消息：同样的长短、标点、语气词。我没发过的，就写短句，可以不完整，可以不带句号。" +
            "不要出现：我理解你的感受、你说得对、确实、当然可以、没问题、我会注意、希望你能理解、抱歉让你、首先、其次、总结。" +
            "不要故意分成稳妥、承诺、低姿态三套模板。不要解释，直接输出 JSON 数组。"

    private const val GROUP =
        "你在替我回微信群，不是助手。只输出一个 JSON 数组，含且仅含 3 条我真的会发出去的话。" +
            "同事就事论事，朋友可以短、可以皮，家人像家里发微信。不要情侣腔，不要「收到，我会尽快处理」这种公文。" +
            "不要出现：我理解、当然可以、没问题、我会注意、希望你能理解。每条尽量不超过 20 个字。不要自己加 @。" +
            "不要解释，直接输出 JSON 数组。"
}
