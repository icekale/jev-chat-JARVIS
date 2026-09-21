package com.jev.probe.core

/** What changed on screen, without taking another screenshot. */
object CapturePace {
    fun textOf(title: String?, messages: List<Msg>): String =
        (title ?: "") + "\u0000" + messages.joinToString("\u0001") {
            "${it.speaker.orEmpty()}\u0002${it.text}"
        }

    /**
     * Name and node-avatar rows have a null bound and stay as extracted.
     * Others take gutter-avatar, then bubble color, then the side already chosen.
     */
    fun recolor(
        messages: List<Msg>,
        bounds: List<BubbleBound?>,
        avatarOf: (BubbleBound) -> String?,
        colorOf: (BubbleBound) -> String?
    ): List<Msg> {
        if (bounds.isEmpty()) return messages
        return messages.mapIndexed { i, m ->
            val b = bounds.getOrNull(i) ?: return@mapIndexed m
            val side = avatarOf(b)?.takeIf { it == "me" || it == "other" }
                ?: colorOf(b)?.takeIf { it == "me" || it == "other" }
                ?: m.side
            if (side == m.side) m
            else m.copy(side = side, speaker = if (side == "other") m.speaker else null)
        }
    }
}
