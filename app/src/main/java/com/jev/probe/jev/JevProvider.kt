package com.jev.probe.jev

/**
 * Where Jev judgments are sent. Reply drafting is always a generative chat
 * call (OpenRouter today); TypeSafe Jev does not generate text.
 */
enum class JevProvider(val id: String) {
    OPENROUTER("openrouter"),
    TYPESAFE("typesafe");

    val decisionsUrl: String
        get() = when (this) {
            OPENROUTER -> "https://openrouter.ai/api/alpha/decisions"
            TYPESAFE -> "https://api.typesafe.ai/v1/systemone"
        }

    val jevModel: String
        get() = when (this) {
            OPENROUTER -> "typesafe/jev-1.13"
            TYPESAFE -> "jev-latest"
        }

    val displayName: String
        get() = when (this) {
            OPENROUTER -> "OpenRouter"
            TYPESAFE -> "TypeSafe 官网"
        }

    companion object {
        fun from(id: String?): JevProvider =
            entries.firstOrNull { it.id == id } ?: OPENROUTER
    }
}
