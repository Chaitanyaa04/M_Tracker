package com.mtracker.app

/**
 * Pure version comparison used by the updater.
 * Kept separate from Android code so it can be unit tested without a device.
 */
object Version {
    private fun nums(v: String) = v.trim()
        .removePrefix("v")
        .removePrefix("V")
        .split(".", "-")
        .mapNotNull { it.toIntOrNull() }

    fun isNewer(tag: String, current: String): Boolean {
        val a = nums(tag)
        val b = nums(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
