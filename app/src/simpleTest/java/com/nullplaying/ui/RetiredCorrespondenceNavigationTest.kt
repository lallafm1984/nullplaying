package com.nullplaying.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RetiredCorrespondenceNavigationTest {
    @Test
    fun `old saved correspondence route redirects to main in every build`() {
        for (debug in listOf(false, true)) {
            for (serverMatching in listOf(false, true)) {
                assertFalse(MenuTab.CORRESPONDENCE in visibleMenuTabsForBuild(debug, serverMatching))
                assertEquals(
                    MenuTab.MAIN,
                    menuTabAfterBuildRedirect(MenuTab.CORRESPONDENCE, debug, serverMatching),
                )
            }
        }
    }
}
