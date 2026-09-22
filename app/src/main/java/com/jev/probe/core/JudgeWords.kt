package com.jev.probe.core

/** Chinese labels shared by the overlay and the draft prompt. */
object JudgeWords {
    val INTENT = mapOf(
        "confirm_you_care" to "确认你在不在乎", "vent_anger" to "在发泄情绪",
        "request_action" to "要你办事", "seek_explanation" to "要个解释",
        "casual_chat" to "随便聊聊", "close_topic" to "事情过去了")
    val NEEDS = mapOf(
        "apology" to "道歉", "action" to "具体行动", "explanation" to "解释",
        "care" to "你的在乎", "nothing" to "（不用做什么）")
    val ACTION = mapOf(
        "check_history" to "翻聊天记录", "apologize" to "先道歉", "give_commitment" to "给承诺",
        "explain" to "解释清楚", "acknowledge" to "接住情绪", "say_less" to "少说两句",
        "make_plan" to "定个安排")
    val GROUP_INTENT = mapOf(
        "ask_you" to "在问你", "assign_task" to "在派活", "coordinate" to "在协调",
        "announce" to "在通知", "joke" to "闲聊/玩笑", "call_out" to "当众点你",
        "off_topic" to "与你无关")
    val GROUP_NEEDS = mapOf(
        "apology" to "你表态道歉", "action" to "你办事", "explanation" to "你解释",
        "care" to "表态/在场", "nothing" to "不用你回")
    val GROUP_ACTION = mapOf(
        "reply_brief" to "简短回一句", "give_fact" to "给具体信息", "volunteer" to "接下任务",
        "wait" to "先别回", "clarify" to "先问清楚", "correct" to "礼貌纠正",
        "deescalate" to "降温")
    val REGISTER = mapOf(
        "work" to "工作", "family" to "家人", "friends" to "朋友", "mixed" to "混合")
    val THREAD = mapOf(
        "new_topic" to "换话题了", "continue" to "还在同一件事",
        "pile_on" to "几个人叠在一起", "resolved" to "已经收住")
}
