package com.mtracker.app

/** Pure head rules shared by the repository, UI, and JVM unit tests. */
object HeadsMath {
    /** Backup validation and the category table both cap head names at 80 characters. */
    fun cleanName(raw: String): String = raw.trim().take(80)

    /** Hidden heads remain in history but never appear in pickers or the quick strip. */
    fun visible(cats: List<Category>): List<Category> =
        cats.filter { !it.hidden }.sortedWith(compareBy<Category> { it.sortOrder }.thenBy { it.name.lowercase() })
}
