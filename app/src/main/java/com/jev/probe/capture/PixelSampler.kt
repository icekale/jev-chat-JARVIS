package com.jev.probe.capture

import android.graphics.Bitmap
import com.jev.probe.core.ChatGeometry

/** Samples a screenshot inside a bubble rect to tell WeChat green (me) from white (other). */
class PixelSampler(private val bmp: Bitmap, private val calibratedOwn: Int? = null) {
    fun weChatSide(left: Int, top: Int, right: Int, bottom: Int): String? {
        val w = right - left
        val h = bottom - top
        if (w < 8 || h < 8) return null
        val pts = arrayOf(
            left + (w * 0.18).toInt() to top + (h * 0.28).toInt(),
            right - (w * 0.18).toInt() to top + (h * 0.28).toInt(),
            left + (w * 0.18).toInt() to top + (h * 0.55).toInt(),
            right - (w * 0.18).toInt() to top + (h * 0.55).toInt(),
            left + w / 2 to top + (h * 0.22).toInt(),
            left + 8 to top + (h * 0.40).toInt(),
            right - 8 to top + (h * 0.40).toInt()
        )
        val colors = ArrayList<Int>(pts.size)
        for ((x, y) in pts) {
            if (x in 0 until bmp.width && y in 0 until bmp.height) {
                colors.add(bmp.getPixel(x, y))
            }
        }
        return ChatGeometry.sideFromWeChatColors(colors, calibratedOwn)
    }

    fun sampleFill(left: Int, top: Int, right: Int, bottom: Int): Int? {
        val w = right - left
        val h = bottom - top
        if (w < 8 || h < 8) return null
        val pts = arrayOf(
            left + (w * 0.18).toInt() to top + (h * 0.28).toInt(),
            right - (w * 0.18).toInt() to top + (h * 0.28).toInt(),
            left + (w * 0.18).toInt() to top + (h * 0.55).toInt(),
            right - (w * 0.18).toInt() to top + (h * 0.55).toInt(),
            left + 8 to top + (h * 0.40).toInt(),
            right - 8 to top + (h * 0.40).toInt()
        )
        var sr = 0
        var sg = 0
        var sb = 0
        var n = 0
        for ((x, y) in pts) {
            if (x !in 0 until bmp.width || y !in 0 until bmp.height) continue
            val c = bmp.getPixel(x, y)
            if (ChatGeometry.isInk(c)) continue
            sr += ChatGeometry.red(c)
            sg += ChatGeometry.green(c)
            sb += ChatGeometry.blue(c)
            n++
        }
        if (n == 0) return null
        return (0xFF shl 24) or ((sr / n) shl 16) or ((sg / n) shl 8) or (sb / n)
    }

    /**
     * Avatar sits in the left or right gutter, top-aligned with the bubble.
     * Photo-like variance there beats a flat wallpaper strip.
     */
    fun weChatAvatarSide(bubbleTop: Int, bubbleBottom: Int): String? {
        val w = bmp.width
        val h = bmp.height
        if (w < 80 || h < 80) return null
        val strip = (w * 0.12).toInt().coerceIn(36, 88)
        val y0 = bubbleTop.coerceIn(0, h - 1)
        val y1 = minOf(bubbleTop + strip, bubbleBottom, h)
        if (y1 - y0 < 12) return null
        val leftVar = ChatGeometry.luminanceVar(sampleRect(4, y0, strip, y1))
        val rightVar = ChatGeometry.luminanceVar(sampleRect(w - strip, y0, w - 4, y1))
        return ChatGeometry.avatarSideFromGutterVars(leftVar, rightVar)
    }

    private fun sampleRect(left: Int, top: Int, right: Int, bottom: Int): List<Int> {
        val l = left.coerceIn(0, bmp.width)
        val r = right.coerceIn(0, bmp.width)
        val t = top.coerceIn(0, bmp.height)
        val b = bottom.coerceIn(0, bmp.height)
        if (r - l < 8 || b - t < 8) return emptyList()
        val out = ArrayList<Int>(16)
        val stepX = ((r - l) / 4).coerceAtLeast(1)
        val stepY = ((b - t) / 4).coerceAtLeast(1)
        var y = t + stepY / 2
        while (y < b) {
            var x = l + stepX / 2
            while (x < r) {
                out.add(bmp.getPixel(x, y))
                x += stepX
            }
            y += stepY
        }
        return out
    }
}
