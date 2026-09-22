package com.jev.probe.core

import kotlin.math.roundToInt

/** Turns a judgment into one Chinese line the reply model must follow. */
object DraftSteer {
    fun line(a: Analysis, prior: ChatMood?, group: Boolean): String {
        val cooled = (a.tensionResolved ?: 0.0) >= Affect.COOL_TENSION
        val now = Affect.usable(a.affect)
        val carried = if (!cooled && now == null) prior?.takeIf { it.affect in Affect.LABELS } else null
        val parts = ArrayList<String>()
        when {
            now != null -> {
                val who = if (group) "这句的情绪是" else "对方现在"
                parts.add(who + Affect.label(now.choice))
            }
            carried != null -> parts.add("上一轮还是${Affect.label(carried.affect)}，这句把握不够，先顺着那个语气")
        }
        val needsMap = if (group) JudgeWords.GROUP_NEEDS else JudgeWords.NEEDS
        val actionMap = if (group) JudgeWords.GROUP_ACTION else JudgeWords.ACTION
        a.sheNeeds?.choice?.let { key ->
            if (key == "nothing") parts.add(if (group) "不用你回" else "对方现在不需要你多做")
            else needsMap[key]?.let { parts.add("对方要的是$it") }
        }
        a.bestAction?.choice?.let { actionMap[it] }?.let { parts.add(it) }
        val hot = now?.choice ?: carried?.affect
        val danger = a.dangerLevel?.score?.roundToInt()
        when {
            danger != null && danger >= 6 -> parts.add("危险 $danger，不要开玩笑，不要先讲道理")
            hot != null && hot in Affect.HOT -> parts.add("不要开玩笑，不要先讲道理")
            danger != null && danger >= 3 -> parts.add("危险 $danger，语气收着点")
        }
        return if (parts.isEmpty()) "" else parts.joinToString("，") + "。"
    }

    /** What the ranking call may see. Low-confidence affect is omitted. */
    fun rankHint(a: Analysis, prior: ChatMood?): MoodHint {
        val cooled = (a.tensionResolved ?: 0.0) >= Affect.COOL_TENSION
        val judged = Affect.usable(a.affect)?.choice
        val priorKey = if (!cooled && judged == null) prior?.affect?.takeIf { it in Affect.LABELS } else null
        return MoodHint(
            priorAffect = priorKey,
            judgedAffect = judged,
            sheNeeds = a.sheNeeds?.choice,
            bestAction = a.bestAction?.choice,
            danger = a.dangerLevel?.score?.roundToInt()
        )
    }
}
