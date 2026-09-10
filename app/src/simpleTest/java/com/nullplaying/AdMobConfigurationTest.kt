package com.nullplaying

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdMobConfigurationTest {
    private val isAdFreeQa = BuildConfig.APPLICATION_ID.endsWith(".battleqa") ||
        BuildConfig.APPLICATION_ID.endsWith(".arenaserverqa") ||
        BuildConfig.APPLICATION_ID.endsWith(".arenaliveserverqa") ||
        BuildConfig.APPLICATION_ID.endsWith(".adventurepreview")

    @Test
    fun appIdUsesAlarmQuestAdMobApplication() {
        val expected = if (isAdFreeQa) "" else
            "ca-app-pub-9163944262143117~4374561480"
        assertEquals(expected, BuildConfig.ADMOB_APP_ID)
    }

    @Test
    fun bannerUnitIsSafeForTheCurrentBuildType() {
        val expected = if (isAdFreeQa) {
            ""
        } else if (BuildConfig.DEBUG) {
            "ca-app-pub-3940256099942544/9214589741"
        } else {
            "ca-app-pub-9163944262143117/1532775725"
        }
        assertEquals(expected, BuildConfig.BANNER_AD_UNIT_ID)
    }

    @Test
    fun rewardedUnitIsSafeForTheCurrentBuildType() {
        when {
            isAdFreeQa -> {
                assertEquals("", BuildConfig.REWARDED_AD_UNIT_ID)
                assertEquals("", BuildConfig.ARENA_REWARDED_AD_UNIT_ID)
            }
            BuildConfig.DEBUG -> {
                assertEquals(
                    "ca-app-pub-3940256099942544/5224354917",
                    BuildConfig.REWARDED_AD_UNIT_ID,
                )
                assertEquals(
                    "ca-app-pub-3940256099942544/5224354917",
                    BuildConfig.ARENA_REWARDED_AD_UNIT_ID,
                )
            }
            else -> {
                assertEquals(
                    "ca-app-pub-9163944262143117/6758932696",
                    BuildConfig.REWARDED_AD_UNIT_ID,
                )
                assertEquals(
                    "ca-app-pub-9163944262143117/1444016513",
                    BuildConfig.ARENA_REWARDED_AD_UNIT_ID,
                )
                assertNotEquals(
                    BuildConfig.REWARDED_AD_UNIT_ID,
                    BuildConfig.ARENA_REWARDED_AD_UNIT_ID,
                )
            }
        }
    }

    @Test
    fun productionRewardedUnitsAreFixedToTheReviewedAdMobUnits() {
        val projectRoot = generateSequence(java.io.File(".").canonicalFile) { it.parentFile }
            .first { java.io.File(it, "app/build.gradle.kts").isFile }
        val gradle = java.io.File(projectRoot, "app/build.gradle.kts").readText()

        assertFalse(gradle.contains("configValue(\"ARENA_REWARDED_AD_UNIT_ID\")"))
        assertTrue(
            gradle.contains(
                "productionArenaRewardedAdUnitId = \"ca-app-pub-9163944262143117/1444016513\"",
            ),
        )
        assertTrue(
            gradle.contains(
                "productionRewardedAdUnitId = \"ca-app-pub-9163944262143117/6758932696\"",
            ),
        )
        assertFalse(gradle.contains("ca-app-pub-9163944262143117/2295193051"))
    }
}
