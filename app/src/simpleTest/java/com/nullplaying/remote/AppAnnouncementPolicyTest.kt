package com.nullplaying.remote

import android.content.Context
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class AppAnnouncementPolicyTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    @Before
    fun clearPreferences() {
        context.getSharedPreferences(AppAnnouncementDisplayStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun `announcement fetch is allowed when no update is available`() {
        assertTrue(appVersionAllowsAnnouncement(null))
    }

    @Test
    fun `optional update still passes the announcement version gate`() {
        assertTrue(appVersionAllowsAnnouncement(updateNotice(isRequired = false)))
    }

    @Test
    fun `required update blocks announcement fetch`() {
        assertFalse(appVersionAllowsAnnouncement(updateNotice(isRequired = true)))
    }

    @Test
    fun `database values map to all supported display types`() {
        assertEquals(
            AppAnnouncementDisplayType.ONCE_AFTER_INSTALL,
            AppAnnouncementDisplayType.fromDatabaseValue("once_after_install"),
        )
        assertEquals(
            AppAnnouncementDisplayType.EVERY_LAUNCH,
            AppAnnouncementDisplayType.fromDatabaseValue("every_launch"),
        )
        assertEquals(
            AppAnnouncementDisplayType.ONCE_PER_DAY,
            AppAnnouncementDisplayType.fromDatabaseValue("once_per_day"),
        )
    }

    @Test
    fun `once after install remains hidden after store recreation`() {
        val announcement = announcement(AppAnnouncementDisplayType.ONCE_AFTER_INSTALL)
        val firstStore = displayStore()

        assertTrue(firstStore.shouldDisplay(announcement))
        firstStore.markDisplayed(announcement)

        assertFalse(displayStore().shouldDisplay(announcement))
    }

    @Test
    fun `every launch is shown once per store instance`() {
        val announcement = announcement(AppAnnouncementDisplayType.EVERY_LAUNCH)
        val firstLaunch = displayStore()

        assertTrue(firstLaunch.shouldDisplay(announcement))
        firstLaunch.markDisplayed(announcement)
        assertFalse(firstLaunch.shouldDisplay(announcement))
        assertTrue(displayStore().shouldDisplay(announcement))
    }

    @Test
    fun `once per day is shared across translations and resets on another day`() {
        val korean = announcement(AppAnnouncementDisplayType.ONCE_PER_DAY)
        val english = korean.copy(id = 2L, title = "Notice", message = "English")
        val firstDay = 1_000_000L
        val laterSameDay = firstDay + 60L * 60L * 1_000L
        val anotherDay = firstDay + 48L * 60L * 60L * 1_000L
        val firstStore = displayStore()

        assertTrue(firstStore.shouldDisplay(korean, firstDay))
        firstStore.markDisplayed(korean, firstDay)

        assertFalse(
            displayStore().shouldDisplay(english, laterSameDay),
        )
        assertTrue(
            displayStore().shouldDisplay(english, anotherDay),
        )
    }

    private fun updateNotice(isRequired: Boolean) = AppUpdateNotice(
        latestVersionCode = 2,
        latestVersionName = "2.0",
        title = "Update",
        message = "Update required",
        updateUrl = "https://example.com/update",
        isRequired = isRequired,
    )

    private fun announcement(displayType: AppAnnouncementDisplayType) = AppAnnouncement(
        id = 1L,
        announcementKey = "service_notice_001",
        title = "공지",
        message = "공지 내용",
        displayType = displayType,
    )

    private fun displayStore() = AppAnnouncementDisplayStore(context, ZoneOffset.UTC)
}
