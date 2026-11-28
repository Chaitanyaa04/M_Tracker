package com.mtracker.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VersionTest {
    @Test
    fun devBuildIsNewerThanFirstStable() {
        assertTrue(Version.isNewer("dev-1.3.25", "1.3.0"))
    }

    @Test
    fun nextMinorStableIsNewerThanDevBuild() {
        assertTrue(Version.isNewer("v1.4.0", "1.3.25"))
    }

    @Test
    fun sameVersionIsNotNewer() {
        assertFalse(Version.isNewer("1.3.0", "1.3.0"))
    }

    @Test
    fun olderVersionIsNotNewer() {
        assertFalse(Version.isNewer("1.2.9", "1.3.0"))
    }
}
