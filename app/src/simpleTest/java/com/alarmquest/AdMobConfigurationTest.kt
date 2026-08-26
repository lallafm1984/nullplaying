package com.alarmquest

import org.junit.Assert.assertEquals
import org.junit.Test

class AdMobConfigurationTest {
    @Test
    fun appIdUsesAlarmQuestAdMobApplication() {
        assertEquals(
            "ca-app-pub-9163944262143117~4374561480",
            BuildConfig.ADMOB_APP_ID,
        )
    }

    @Test
    fun bannerUnitIsSafeForTheCurrentBuildType() {
        val expected = if (BuildConfig.DEBUG) {
            "ca-app-pub-3940256099942544/9214589741"
        } else {
            "ca-app-pub-9163944262143117/1532775725"
        }
        assertEquals(expected, BuildConfig.BANNER_AD_UNIT_ID)
    }

    @Test
    fun rewardedUnitIsSafeForTheCurrentBuildType() {
        val expected = if (BuildConfig.DEBUG) {
            "ca-app-pub-3940256099942544/5224354917"
        } else {
            "ca-app-pub-9163944262143117/2295193051"
        }
        assertEquals(expected, BuildConfig.REWARDED_AD_UNIT_ID)
    }
}
