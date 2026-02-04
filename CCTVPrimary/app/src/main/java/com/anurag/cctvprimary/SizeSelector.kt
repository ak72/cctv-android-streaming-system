package com.anurag.cctvprimary

import android.util.Size

internal object SizeSelector {
    private fun area(s: Size): Long = s.width.toLong() * s.height.toLong()

    private fun is4by3Portrait(s: Size): Boolean {
        // We want 3:4 (portrait) content: width/height ≈ 0.75
        // Accept exact multiples (e.g. 720x960, 1080x1440) and near-exact (due to odd sizes).
        val w = s.width
        val h = s.height
        if (w <= 0 || h <= 0) return false
        // exact ratio check first
        if (w * 4 == h * 3) return true
        // tolerant check: |(w/h) - 0.75| <= 1.5%
        val r = w.toDouble() / h.toDouble()
        return kotlin.math.abs(r - 0.75) <= 0.015
    }

    fun intersect4by3Portrait(vararg lists: List<Size>): List<Size> {
        if (lists.isEmpty()) return emptyList()
        var set = lists[0].toSet()
        for (i in 1 until lists.size) {
            set = set.intersect(lists[i].toSet())
        }
        return set.filter { is4by3Portrait(it) }.sortedByDescending { area(it) }
    }

    fun pickBestAtOrBelow(candidates: List<Size>, desiredW: Int, desiredH: Int): Size? {
        if (candidates.isEmpty()) return null
        val desiredArea = desiredW.toLong() * desiredH.toLong()
        // Prefer <= desired area (closest lower), else smallest higher.
        val lowerOrEq = candidates.filter { area(it) <= desiredArea }
        if (lowerOrEq.isNotEmpty()) {
            return lowerOrEq.minByOrNull { desiredArea - area(it) }
        }
        val higher = candidates.filter { area(it) > desiredArea }
        return higher.minByOrNull { area(it) - desiredArea }
    }
}

