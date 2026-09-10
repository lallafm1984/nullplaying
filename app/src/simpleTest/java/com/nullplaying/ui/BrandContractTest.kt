package com.nullplaying.ui

import android.graphics.BitmapFactory
import com.nullplaying.BuildConfig
import com.nullplaying.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class BrandContractTest {
    @Test
    fun `installed app label and package match the build variant contract`() {
        val context = RuntimeEnvironment.getApplication()
        val applicationInfo = context.packageManager.getApplicationInfo(context.packageName, 0)
        val expectedLabel = when {
            BuildConfig.APPLICATION_ID.endsWith(".adventurepreview") -> "NULL PLAYING 모험 검증"
            BuildConfig.APPLICATION_ID.endsWith(".battleqa") -> "AlarmQuest Arena QA"
            BuildConfig.APPLICATION_ID.endsWith(".offlineqa") -> "AlarmQuest Offline QA"
            BuildConfig.APPLICATION_ID.endsWith(".eeaqa") -> "AlarmQuest EEA QA"
            else -> "NULL PLAYING"
        }

        assertEquals(expectedLabel, context.getString(R.string.app_name))
        assertEquals(R.string.app_name, applicationInfo.labelRes)
        assertEquals(BuildConfig.APPLICATION_ID, context.packageName)
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
