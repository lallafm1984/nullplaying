package com.nullplaying.ui

import android.content.Context
import com.nullplaying.localization.AppLanguage
import com.nullplaying.remote.AppAnnouncementDisplayStore
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
class WhatsNewNoticeTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun clearNoticePreferences() {
        context.getSharedPreferences(AppAnnouncementDisplayStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun `notice targets this update and does not interrupt a fresh installation`() {
        assertTrue(whatsNewNoticeEligible(25, wasUpdated = true))
        assertFalse(whatsNewNoticeEligible(25, wasUpdated = false))
        assertFalse(whatsNewNoticeEligible(18, wasUpdated = true))
        assertFalse(whatsNewNoticeEligible(19, wasUpdated = true))
        assertFalse(whatsNewNoticeEligible(20, wasUpdated = true))
        assertFalse(whatsNewNoticeEligible(21, wasUpdated = true))
        assertFalse(whatsNewNoticeEligible(22, wasUpdated = true))
        assertFalse(whatsNewNoticeEligible(23, wasUpdated = true))
        assertFalse(whatsNewNoticeEligible(24, wasUpdated = true))
        assertFalse(whatsNewNoticeEligible(26, wasUpdated = true))
    }

    @Test
    fun `closing persists across launches and language changes`() {
        val ko = whatsNewNotice(AppLanguage.KOREAN)
        val firstLaunch = AppAnnouncementDisplayStore(context)
        assertTrue(firstLaunch.shouldDisplay(ko))
        firstLaunch.markDisplayed(ko)
        AppLanguage.entries.forEach { language ->
            assertFalse(AppAnnouncementDisplayStore(context).shouldDisplay(whatsNewNotice(language)))
        }
    }

    @Test
    fun `closing a prior internal notice does not hide the version 25 update`() {
        val notice = whatsNewNotice(AppLanguage.KOREAN)
        val store = AppAnnouncementDisplayStore(context)
        store.markDisplayed(notice.copy(id = -19L, announcementKey = "local-whats-new-0.5.1"))
        store.markDisplayed(notice.copy(id = -20L, announcementKey = "local-whats-new-0.5.1-20"))
        store.markDisplayed(notice.copy(id = -21L, announcementKey = "local-whats-new-0.5.1-21"))
        store.markDisplayed(notice.copy(id = -22L, announcementKey = "local-whats-new-0.5.1-22"))
        store.markDisplayed(notice.copy(id = -23L, announcementKey = "local-whats-new-0.5.1-23"))
        store.markDisplayed(notice.copy(id = -24L, announcementKey = "local-whats-new-0.5.1-24"))

        assertTrue(AppAnnouncementDisplayStore(context).shouldDisplay(notice))
        store.markDisplayed(notice)
        assertFalse(AppAnnouncementDisplayStore(context).shouldDisplay(notice))
    }

    @Test
    fun `a notice not yet closed remains available after process recreation`() {
        val notice = whatsNewNotice(AppLanguage.KOREAN)
        assertTrue(AppAnnouncementDisplayStore(context).shouldDisplay(notice))
        assertTrue(AppAnnouncementDisplayStore(context).shouldDisplay(notice))
    }

    @Test
    fun `all languages explain four features with short separated paragraphs`() {
        AppLanguage.entries.forEach { language ->
            val notice = whatsNewNotice(language)
            assertTrue(notice.title.contains("0.5.1"))
            val paragraphs = notice.message.split("\n\n")
            assertEquals(4, paragraphs.size)
            assertTrue(paragraphs.all { it.lines().size == 2 && it.length <= 155 })
            assertFalse(notice.message.contains("XP"))
        }
    }
}
