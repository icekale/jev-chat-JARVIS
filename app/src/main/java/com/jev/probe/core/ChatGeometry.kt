package com.jev.probe.core

/** Pure geometry / color helpers for chat-bubble side detection. */
object ChatGeometry {
    fun red(c: Int) = (c ushr 16) and 0xFF
    fun green(c: Int) = (c ushr 8) and 0xFF
    fun blue(c: Int) = c and 0xFF

    /** Near-black ink (message text), skip when sampling the bubble fill. */
    fun isInk(c: Int): Boolean {
        val r = red(c); val g = green(c); val b = blue(c)
        return r < 55 && g < 55 && b < 55
    }

    /**
     * WeChat's own bubble: the classic light green (#95EC69) and the darker
     * green used in night mode. Green channel must clearly beat red and blue.
     */
    fun isWeChatOwnGreen(c: Int): Boolean {
        val r = red(c); val g = green(c); val b = blue(c)
        if (g < 88) return false
        return (g - r) >= 20 && (g - b) >= 25
    }

    /** Opposite bubble: white / light gray, or a near-neutral gray (incl. night mode). */
    fun isWeChatOtherBubble(c: Int): Boolean {
        if (isWeChatOwnGreen(c) || isInk(c)) return false
        val r = red(c); val g = green(c); val b = blue(c)
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val chroma = max - min
        if (min >= 200 && chroma < 28) return true
        return chroma < 14 && max in 56..199
    }

    /**
     * Green is unique to own bubbles, so one green sample is enough.
     * White/gray is shared with the chat wallpaper — only wins when no green.
     */
    fun colorClose(a: Int, b: Int, maxDist: Int = 44): Boolean {
        val dr = red(a) - red(b)
        val dg = green(a) - green(b)
        val db = blue(a) - blue(b)
        return dr * dr + dg * dg + db * db <= maxDist * maxDist
    }

    fun sideFromWeChatColors(samples: List<Int>, calibratedOwn: Int? = null): String? {
        var own = 0
        var other = 0
        val cal = if (calibratedOwn != null && calibratedOwn != 0) calibratedOwn else null
        for (c in samples) {
            if (isInk(c)) continue
            if (cal != null && colorClose(c, cal)) own++
            else if (isWeChatOwnGreen(c)) own++
            else if (isWeChatOtherBubble(c)) other++
        }
        return when {
            own >= 1 -> "me"
            other >= 1 -> "other"
            else -> null
        }
    }

    /**
     * Name above the bubble is WeChat's "other" marker (own messages have no name).
     * Avatar side beats color (skins change green). Color beats X-cluster.
     * Unknown signals fall through; [cluster] is the last resort and should always
     * be "me" or "other".
     */
    fun decideSide(
        named: Boolean,
        avatar: String?,
        color: String?,
        cluster: String
    ): String {
        if (named) return "other"
        if (avatar == "me" || avatar == "other") return avatar
        if (color == "me" || color == "other") return color
        return if (cluster == "me") "me" else "other"
    }

    fun avatarSideFromCx(cx: Int, width: Int): String? = when {
        width <= 0 -> null
        cx > width * 0.55 -> "me"
        cx < width * 0.45 -> "other"
        else -> null
    }

    fun luminanceVar(colors: List<Int>): Double {
        if (colors.size < 4) return 0.0
        var sum = 0.0
        val ys = DoubleArray(colors.size)
        for (i in colors.indices) {
            val c = colors[i]
            val y = 0.299 * red(c) + 0.587 * green(c) + 0.114 * blue(c)
            ys[i] = y
            sum += y
        }
        val mean = sum / ys.size
        var acc = 0.0
        for (y in ys) {
            val d = y - mean
            acc += d * d
        }
        return acc / ys.size
    }

    /**
     * Avatar photos are high-variance; wallpaper / empty gutter is flat.
     * One noisy gutter and one flat gutter is enough. Photo wallpaper on both
     * sides stays unknown unless one side is clearly twice the other.
     */
    fun avatarSideFromGutterVars(
        leftVar: Double,
        rightVar: Double,
        minVar: Double = 350.0
    ): String? {
        val left = leftVar >= minVar
        val right = rightVar >= minVar
        return when {
            left && !right -> "other"
            right && !left -> "me"
            left && right && leftVar > rightVar * 2 -> "other"
            left && right && rightVar > leftVar * 2 -> "me"
            else -> null
        }
    }

    /**
     * Midpoint between the two 1-D clusters of bubble center-X values.
     * Null when there is only one side or the gap is too small to trust.
     */
    fun clusterMidX(centers: List<Int>, minGap: Int = 60): Int? {
        if (centers.size < 2) return null
        val sorted = centers.sorted()
        var bestGap = 0
        var split = 0
        for (i in 1 until sorted.size) {
            val gap = sorted[i] - sorted[i - 1]
            if (gap > bestGap) {
                bestGap = gap
                split = i
            }
        }
        if (bestGap < minGap || split == 0) return null
        val left = sorted.take(split).average()
        val right = sorted.drop(split).average()
        return ((left + right) / 2.0).toInt()
    }
}
