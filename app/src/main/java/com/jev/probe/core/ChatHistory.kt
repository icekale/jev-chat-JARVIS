package com.jev.probe.core

/** Rolling per-conversation transcript. Visible screen is a window; we stitch. */
object ChatHistory {
    const val CAP = 40
    const val MAX_CHATS = 16

    fun keyOf(m: Msg): String = "${m.side}\u0000${m.speaker ?: ""}\u0000${m.text}"

    fun chatKey(pkg: String?, title: String?): String? {
        if (pkg.isNullOrBlank()) return null
        return "$pkg|${title?.trim().orEmpty()}"
    }

    fun merge(prev: List<Msg>, visible: List<Msg>, cap: Int = CAP): List<Msg> {
        if (visible.isEmpty()) return prev.takeLast(cap)
        if (prev.isEmpty()) return visible.takeLast(cap)
        if (prev == visible) return prev.takeLast(cap)

        val max = minOf(prev.size, visible.size)
        val minOverlap = if (prev.size >= 2 && visible.size >= 2) 2 else 1

        for (len in max downTo minOverlap) {
            if (same(prev.takeLast(len), visible.take(len))) {
                return (prev + visible.drop(len)).takeLast(cap)
            }
        }
        for (len in max downTo minOverlap) {
            if (same(visible.takeLast(len), prev.take(len))) {
                return (visible.dropLast(len) + prev).takeLast(cap)
            }
        }
        val visKeys = visible.map(::keyOf)
        val prevKeys = prev.map(::keyOf)
        if (indexOfSublist(prevKeys, visKeys) >= 0) return prev.takeLast(cap)

        val seen = prevKeys.toHashSet()
        val extra = visible.filter { seen.add(keyOf(it)) }
        return (prev + extra).takeLast(cap)
    }

    fun evict(store: LinkedHashMap<String, List<Msg>>, max: Int = MAX_CHATS) {
        while (store.size > max) {
            val oldest = store.keys.firstOrNull() ?: break
            store.remove(oldest)
        }
    }

    private fun same(a: List<Msg>, b: List<Msg>): Boolean {
        if (a.size != b.size) return false
        for (i in a.indices) if (keyOf(a[i]) != keyOf(b[i])) return false
        return true
    }

    private fun indexOfSublist(hay: List<String>, needle: List<String>): Int {
        if (needle.isEmpty() || needle.size > hay.size) return -1
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) {
                if (hay[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }
}
