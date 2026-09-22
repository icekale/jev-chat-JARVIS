package com.jev.probe.core

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.jev.probe.jev.JevProvider

/**
 * App-private config store. Holds the Jev provider + key, optional reply-draft
 * key, model choices, the relationship description used in Jev's state, and
 * the conversation whitelist.
 *
 * Key handling: EncryptedSharedPreferences (AES256-GCM) with a one-shot
 * migrate from the old plaintext file. Keys are never logged or in git.
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences = openStore(context.applicationContext)

    var jevProvider: JevProvider
        get() = JevProvider.from(sp.getString(K_PROVIDER, JevProvider.OPENROUTER.id))
        set(v) = sp.edit().putString(K_PROVIDER, v.id).apply()

    /** Key for the selected Jev endpoint (OpenRouter or TypeSafe). */
    var openRouterKey: String
        get() = sp.getString(K_KEY, "") ?: ""
        set(v) = sp.edit().putString(K_KEY, v.trim()).apply()

    /**
     * Optional key used only to draft the 3 candidate replies (any OpenAI-compatible
     * endpoint). Blank means: reuse [openRouterKey] when the chat URL is OpenRouter
     * and the Jev provider is also OpenRouter; otherwise skip drafting.
     */
    var replyKey: String
        get() = sp.getString(K_REPLY_KEY, "") ?: ""
        set(v) = sp.edit().putString(K_REPLY_KEY, v.trim()).apply()

    /** OpenAI-compatible Chat Completions base URL (with or without /chat/completions). */
    var replyBaseUrl: String
        get() = (sp.getString(K_REPLY_BASE, DEFAULT_REPLY_BASE) ?: DEFAULT_REPLY_BASE).ifBlank { DEFAULT_REPLY_BASE }
        set(v) = sp.edit().putString(K_REPLY_BASE, v.trim().ifBlank { DEFAULT_REPLY_BASE }).apply()

    /** Generative model id for drafting the 3 candidate replies. */
    var replyModel: String
        get() = sp.getString(K_REPLY_MODEL, DEFAULT_REPLY_MODEL) ?: DEFAULT_REPLY_MODEL
        set(v) = sp.edit().putString(K_REPLY_MODEL, v.trim()).apply()

    /** Free-text describing who the other person is; goes into Jev's state. */
    var relationship: String
        get() = sp.getString(K_REL, DEFAULT_REL) ?: DEFAULT_REL
        set(v) = sp.edit().putString(K_REL, v).apply()

    /** Master on/off for showing the overlay + running analysis. */
    var enabled: Boolean
        get() = sp.getBoolean(K_ENABLED, true)
        set(v) = sp.edit().putBoolean(K_ENABLED, v).apply()

    /**
     * Conversation whitelist: titles the assistant is allowed to act on. Empty
     * set means "all conversations". Stored as a plain string set.
     */
    var whitelist: Set<String>
        get() = sp.getStringSet(K_WHITELIST, emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet(K_WHITELIST, v).apply()

    /** Overlay panel opacity, 60..100 (%). Lower lets the chat show through. */
    var overlayOpacity: Int
        get() = sp.getInt(K_OPACITY, 92).coerceIn(60, 100)
        set(v) = sp.edit().putInt(K_OPACITY, v.coerceIn(60, 100)).apply()

    /** Remembered vertical position of the bubble (px); -1 = default. */
    var bubbleY: Int
        get() = sp.getInt(K_BUBBLE_Y, -1)
        set(v) = sp.edit().putInt(K_BUBBLE_Y, v).apply()

    /** Remembered horizontal position of the bubble (px); -1 = default. */
    var bubbleX: Int
        get() = sp.getInt(K_BUBBLE_X, -1)
        set(v) = sp.edit().putInt(K_BUBBLE_X, v).apply()

    /** Auto-analyze on every incoming message; if false, user taps to analyze. */
    var autoAnalyze: Boolean
        get() = sp.getBoolean(K_AUTO, true)
        set(v) = sp.edit().putBoolean(K_AUTO, v).apply()

    /** Group chats: only @me (default), every message, or manual only. */
    var groupAuto: GroupAuto
        get() = GroupAuto.from(sp.getString(K_GROUP_AUTO, GroupAuto.OFF.id))
        set(v) = sp.edit().putString(K_GROUP_AUTO, v.id).apply()

    /** Nicknames used to detect @you in a group. Empty → only @我 / @所有人. */
    var myNicknames: Set<String>
        get() = sp.getStringSet(K_NICKS, emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet(K_NICKS, v).apply()

    /** Relationship text used only for group-chat Jev state. */
    var groupRelationship: String
        get() = sp.getString(K_GROUP_REL, DEFAULT_GROUP_REL) ?: DEFAULT_GROUP_REL
        set(v) = sp.edit().putString(K_GROUP_REL, v).apply()

    /** Extra words that make a group message "about me" (deadline, 你负责…). */
    var groupWatch: Set<String>
        get() = sp.getStringSet(K_WATCH, emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet(K_WATCH, v).apply()

    /** Show a local digest on the idle bubble when not auto-analyzing. */
    var groupDigest: Boolean
        get() = sp.getBoolean(K_DIGEST, true)
        set(v) = sp.edit().putBoolean(K_DIGEST, v).apply()

    /** Prefix @name on fill when Jev picked a reply target. */
    var groupAtOnFill: Boolean
        get() = sp.getBoolean(K_AT_FILL, true)
        set(v) = sp.edit().putBoolean(K_AT_FILL, v).apply()

    /** User-marked own-bubble fill color (ARGB). 0 = not calibrated. */
    var myBubbleColor: Int
        get() = sp.getInt(K_MY_BUBBLE, 0)
        set(v) = sp.edit().putInt(K_MY_BUBBLE, v).apply()

    fun isAllowed(title: String?): Boolean {
        val wl = whitelist
        if (wl.isEmpty()) return true
        if (title == null) return false
        return wl.any { title.contains(it) }
    }

    fun hasKey(): Boolean = openRouterKey.isNotBlank()

    companion object {
        private const val K_PROVIDER = "jev_provider"
        private const val K_KEY = "openrouter_key"
        private const val K_REPLY_KEY = "reply_key"
        private const val K_REPLY_BASE = "reply_base_url"
        private const val K_REPLY_MODEL = "reply_model"
        private const val K_REL = "relationship"
        private const val K_ENABLED = "enabled"
        private const val K_WHITELIST = "whitelist"
        private const val K_OPACITY = "overlay_opacity"
        private const val K_BUBBLE_Y = "bubble_y"
        private const val K_BUBBLE_X = "bubble_x"
        private const val K_AUTO = "auto_analyze"
        private const val K_GROUP_AUTO = "group_auto"
        private const val K_NICKS = "my_nicknames"
        private const val K_GROUP_REL = "group_relationship"
        private const val K_WATCH = "group_watch"
        private const val K_DIGEST = "group_digest"
        private const val K_AT_FILL = "group_at_fill"
        private const val K_MY_BUBBLE = "my_bubble_color"

        const val DEFAULT_REPLY_BASE = "https://openrouter.ai/api/v1"
        // Default stays the OpenRouter DeepSeek id; change the model when you
        // point replyBaseUrl at api.openai.com / DeepSeek / a local proxy.
        const val DEFAULT_REPLY_MODEL = "deepseek/deepseek-chat-v3.1"
        const val DEFAULT_REL = "对方是我的伴侣；from=me 的是我发的，from=other 的是对方发的"
        const val DEFAULT_GROUP_REL =
            "这是一个多人群聊。from=me 是我，speaker 是群成员昵称。先判断是不是在叫我，再决定回不回。不要用私聊情侣语气。"

        /** Accepts a host, /v1 base, or a full /chat/completions URL. */
        fun chatCompletionsUrl(raw: String): String {
            var t = raw.trim().trimEnd('/')
            if (t.isEmpty()) t = DEFAULT_REPLY_BASE.trimEnd('/')
            return when {
                t.endsWith("/chat/completions") -> t
                t.endsWith("/v1") || t.endsWith("/api/v1") -> "$t/chat/completions"
                else -> "$t/v1/chat/completions"
            }
        }

        fun isOpenRouterChat(raw: String): Boolean =
            raw.contains("openrouter.ai", ignoreCase = true)

        private const val PLAIN_FILE = "jev_assistant"
        private const val ENC_FILE = "jev_assistant_enc"

        private fun openStore(ctx: Context): SharedPreferences {
            val enc = runCatching { encrypted(ctx) }.onFailure {
                Log.w("JEVASSIST", "encrypted prefs unavailable, using private store")
            }.getOrNull()
            val plain = ctx.getSharedPreferences(PLAIN_FILE, Context.MODE_PRIVATE)
            if (enc != null) {
                migrate(plain, enc)
                return enc
            }
            return plain
        }

        private fun encrypted(ctx: Context): SharedPreferences {
            val master = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            return EncryptedSharedPreferences.create(
                ENC_FILE,
                master,
                ctx,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }

        @Suppress("UNCHECKED_CAST")
        private fun migrate(from: SharedPreferences, to: SharedPreferences) {
            val all = from.all
            if (all.isEmpty() || to.all.isNotEmpty()) return
            val ed = to.edit()
            for ((k, v) in all) {
                when (v) {
                    is String -> ed.putString(k, v)
                    is Boolean -> ed.putBoolean(k, v)
                    is Int -> ed.putInt(k, v)
                    is Set<*> -> ed.putStringSet(k, v as Set<String>)
                }
            }
            ed.commit()
            from.edit().clear().commit()
        }
    }
}
