package com.alarmquest.ui

import android.graphics.BitmapFactory
import com.alarmquest.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BrandContractTest {
    @Test
    fun `installed app label is NULL PLAYING while update package stays compatible`() {
        val context = RuntimeEnvironment.getApplication()
        val applicationInfo = context.packageManager.getApplicationInfo(context.packageName, 0)

        assertEquals("NULL PLAYING", context.getString(R.string.app_name))
        assertEquals(R.string.app_name, applicationInfo.labelRes)
        assertEquals("com.alarmquest", context.packageName)
    }

    @Test
    fun `title uses the production transparent image wordmark`() {
        val context = RuntimeEnvironment.getApplication()
        val bitmap = BitmapFactory.decodeResource(
            context.resources,
            R.drawable.title_logo_null_playing_v1,
        )

        assertEquals(2_048, bitmap.width)
        assertEquals(452, bitmap.height)
        val pngHeader = context.resources
            .openRawResource(R.drawable.title_logo_null_playing_v1)
            .use { it.readNBytes(26) }
        assertEquals(6, pngHeader[25].toInt() and 0xFF)
    }
}
