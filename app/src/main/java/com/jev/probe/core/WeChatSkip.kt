package com.jev.probe.core

/**
 * Conversations that are not a person/group: File Transfer, WeChat's own
 * service accounts, and official / service / subscription accounts.
 */
object WeChatSkip {

    private val SYSTEM_CN = setOf(
        "文件传输助手",
        "微信团队",
        "微信安全中心",
        "微信安全",
        "微信支付",
        "微信支付商家助手",
        "微信收款助手",
        "微信收款商业版",
        "服务通知",
        "微信运动",
        "微信游戏",
        "微信游戏中心",
        "微信发票助手",
        "微信名片夹",
        "微信电话本",
        "微信指数",
        "群发助手",
        "语音记事本",
        "漂流瓶",
        "微信客服",
        "腾讯客服",
        "腾讯新闻",
        "微信广告",
        "微信小店",
        "微信商家",
        "微信分付",
        "理财通",
        "腾讯理财通",
        "QQ邮箱提醒",
        "QQ邮箱",
        "微信读书",
        "微信派",
        "看一看",
        "搜一搜",
        "订阅号",
        "订阅号消息",
        "服务号",
        "朋友推荐"
    )

    private val SYSTEM_EN = setOf(
        "file transfer",
        "file helper",
        "file transfer assistant",
        "wechat team",
        "weixin team",
        "wechat pay",
        "service notifications",
        "subscription accounts",
        "official accounts",
        "service account"
    )

    fun normalize(title: String): String =
        title.trim().replace(Regex("\\s+"), " ")

    fun isSystemTitle(title: String?): Boolean {
        val n = normalize(title ?: return false)
        if (n.isEmpty()) return false
        if (n in SYSTEM_CN) return true
        if (SYSTEM_EN.any { it.equals(n, ignoreCase = true) }) return true
        if (n.startsWith("订阅号") || n.startsWith("服务通知")) return true
        return false
    }

    fun isOfficialChrome(id: String, text: String?, desc: String): Boolean {
        val t = text?.trim().orEmpty()
        if (t == "公众号" || t == "服务号" || t == "订阅号") return true
        if (t.endsWith("公众号") || t.endsWith("服务号") || t.endsWith("订阅号")) return true
        if (desc.contains("公众号") || desc.contains("服务号") || desc.contains("订阅号")) return true
        if (desc.contains("official account", ignoreCase = true)) return true
        if (desc.contains("service account", ignoreCase = true)) return true
        val idl = id.lowercase()
        if (idl.contains("brandservice") || idl.contains("brand_service")) return true
        if (idl.contains("biz_menu") || idl.contains("custom_menu") || idl.contains("mp_menu")) return true
        if (idl.contains("brand") && (idl.contains("menu") || idl.contains("biz"))) return true
        return false
    }

    fun reason(title: String?, officialChrome: Boolean): String? {
        if (isSystemTitle(title)) return "system"
        if (officialChrome) return "official"
        val n = normalize(title ?: "")
        if (n.endsWith("公众号") || n.endsWith("服务号") || n.contains("的公众号")) return "official"
        return null
    }

    val TAB_LABELS = setOf(
        "微信", "通讯录", "发现", "我",
        "Chats", "Contacts", "Discover", "Me"
    )

    /** Main tabs, Moments, Channels, scan/pay, settings — not a conversation. */
    private val NON_CHAT_CN = setOf(
        "微信", "通讯录", "发现", "我",
        "朋友圈", "视频号", "直播", "直播和附近",
        "扫一扫", "收付款", "收付款与服务", "钱包", "支付",
        "设置", "收藏", "卡包", "表情", "我的表情",
        "附近的人", "摇一摇", "看一看", "搜一搜", "搜索",
        "小程序", "游戏", "购物", "听一听", "状态",
        "微信运动", "腾讯新闻", "朋友推荐消息",
        "聊天信息", "群聊信息"
    )

    private val NON_CHAT_EN = setOf(
        "wechat", "chats", "contacts", "discover", "me",
        "moments", "channels", "live", "scan", "money",
        "settings", "favorites", "search", "mini programs"
    )

    fun isNonChatTitle(title: String?): Boolean {
        val n = normalize(title ?: return false)
        if (n.isEmpty()) return false
        if (n in NON_CHAT_CN || n in SYSTEM_CN) return true
        if (NON_CHAT_EN.any { it.equals(n, ignoreCase = true) }) return true
        if (SYSTEM_EN.any { it.equals(n, ignoreCase = true) }) return true
        if (n.startsWith("朋友圈") || n.startsWith("视频号")) return true
        return false
    }

    fun looksLikeMoments(id: String, text: String?, desc: String, top: Int, height: Int): Boolean {
        val idl = id.lowercase()
        if (idl.contains("sns_") || idl.contains("/sns") || idl.contains("album_comment")) return true
        if (idl.contains("sns") && (idl.contains("timeline") || idl.contains("feed") || idl.contains("album"))) {
            return true
        }
        val t = text?.trim().orEmpty()
        if ((t == "朋友圈" || t.equals("Moments", true)) && top < height * 0.22) return true
        if (desc == "朋友圈" || desc.contains("朋友圈封面") || desc.equals("Moments", true)) return true
        return false
    }

    fun looksLikeFinder(id: String, text: String?, desc: String, top: Int, height: Int): Boolean {
        val idl = id.lowercase()
        if (idl.contains("finder") || idl.contains("video_channel")) return true
        val t = text?.trim().orEmpty()
        if ((t == "视频号" || t == "直播") && height > 0 && top < height * 0.22) return true
        return desc == "视频号" || desc == "直播"
    }

    fun looksLikeMiniProgram(id: String): Boolean {
        val idl = id.lowercase()
        return idl.contains("appbrand") || idl.contains("miniprogram") || idl.contains("mini_program")
    }

    /** Bottom composer or hold-to-talk. Moments comment box is caught by the page title first. */
    fun isChatComposer(editable: Boolean, text: String?, top: Int, height: Int): Boolean {
        if (height <= 0) return false
        if (editable && top > height * 0.58) return true
        val t = text?.trim().orEmpty()
        return (t == "按住 说话" || t == "按住说话" || t.equals("Hold to Talk", true)) &&
            top > height * 0.58
    }

    /**
     * null when this window may be a conversation.
     * Structural pages win even if a comment box is on screen.
     */
    fun pageSkip(
        title: String?,
        momentsChrome: Boolean,
        tabCount: Int,
        finderChrome: Boolean,
        miniProgram: Boolean
    ): String? {
        if (momentsChrome || finderChrome || miniProgram || tabCount >= 3) return "not_chat"
        if (isNonChatTitle(title)) return "not_chat"
        return null
    }
}
