package com.mtracker.app

import kotlin.test.Test
import kotlin.test.assertEquals

class HeadsMathTest {
    @Test
    fun headNamesStayWithinBackupLimits() {
        assertEquals("Food", HeadsMath.cleanName("  Food  "))
        assertEquals(80, HeadsMath.cleanName("x".repeat(140)).length)
        assertEquals("", HeadsMath.cleanName("   "))
    }

    @Test
    fun hiddenHeadsLeavePickersButKeepTheirOrder() {
        val cats = listOf(
            Category("Later", "EXPENSE", sortOrder = 2),
            Category("Hidden", "EXPENSE", sortOrder = 0, hidden = true),
            Category("First", "EXPENSE", sortOrder = 1)
        )
        assertEquals(listOf("First", "Later"), HeadsMath.visible(cats).map { it.name })
    }
}
