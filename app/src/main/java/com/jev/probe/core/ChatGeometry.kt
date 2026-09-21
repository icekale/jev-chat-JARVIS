package com.jev.probe.core

/** Pure geometry helpers for chat-bubble side detection. */
object ChatGeometry {
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
