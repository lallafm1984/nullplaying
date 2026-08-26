package com.alarmquest.localization

import android.content.Context
import java.util.Locale
import org.junit.After
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
@Config(sdk = [35])
class GameLocalizationTest {
    private val originalLocale = Locale.getDefault()
    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        context.getSharedPreferences("game_language_preferences", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        GameLocalization.initialize(context)
    }

    @After
    fun tearDown() {
        Locale.setDefault(originalLocale)
    }

    @Test
    fun `fresh install selects a supported device language and otherwise uses English`() {
        assertEquals(AppLanguage.KOREAN, AppLanguage.fromDevice(Locale.KOREA))
        assertEquals(AppLanguage.JAPANESE, AppLanguage.fromDevice(Locale.JAPAN))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromDevice(Locale.US))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromDevice(Locale.FRANCE))
    }

    @Test
    fun `explicit language selection persists independently from device locale`() {
        Locale.setDefault(Locale.JAPAN)
        val firstRun = GameLanguageStore(context)
        assertEquals(AppLanguage.JAPANESE, firstRun.language.value)
        assertFalse(firstRun.hasExplicitSelection())

        firstRun.setLanguage(AppLanguage.JAPANESE)
        assertTrue(firstRun.hasExplicitSelection())

        Locale.setDefault(Locale.KOREA)
        val reopened = GameLanguageStore(context)
        assertEquals(AppLanguage.JAPANESE, reopened.language.value)
    }

    @Test
    fun `catalog translates exact and dynamic text in all three languages`() {
        assertEquals("설정", GameLocalization.translate("설정", AppLanguage.KOREAN))
        assertEquals("Settings", GameLocalization.translate("설정", AppLanguage.ENGLISH))
        assertEquals("設定", GameLocalization.translate("설정", AppLanguage.JAPANESE))
        assertEquals(
            "Delete data",
            GameLocalization.translate("데이터 삭제 요청", AppLanguage.ENGLISH),
        )
        assertEquals(
            "データ削除",
            GameLocalization.translate("데이터 삭제 요청", AppLanguage.JAPANESE),
        )
        assertEquals(
            "Open request page",
            GameLocalization.translate("삭제 요청 페이지로 이동", AppLanguage.ENGLISH),
        )
        assertEquals("Completed", GameLocalization.translate("완결", AppLanguage.ENGLISH))
        val classNames = listOf(
            "파이터" to ("Fighter" to "ファイター"),
            "시프" to ("Thief" to "シーフ"),
            "레인져" to ("Ranger" to "レンジャー"),
            "메이지" to ("Mage" to "メイジ"),
            "클래릭" to ("Cleric" to "クレリック"),
            "팔라딘" to ("Paladin" to "パラディン"),
        )
        classNames.forEach { (korean, localized) ->
            assertEquals(localized.first, GameLocalization.translate(korean, AppLanguage.ENGLISH))
            assertEquals(localized.second, GameLocalization.translate(korean, AppLanguage.JAPANESE))
        }
        assertEquals("Stat Roll", GameLocalization.translate("능력치 굴림", AppLanguage.ENGLISH))
        assertEquals("能力値決定", GameLocalization.translate("능력치 굴림", AppLanguage.JAPANESE))
        assertEquals("Uncommon", GameLocalization.translate("고급", AppLanguage.ENGLISH))
        assertEquals("アンコモン", GameLocalization.translate("고급", AppLanguage.JAPANESE))
        assertEquals(
            "Volume 1 · Ash Border",
            GameLocalization.translate("제1권 · 잿빛 국경", AppLanguage.ENGLISH),
        )
        assertEquals(
            "Lina's offline adventure time has run out.",
            GameLocalization.translate(
                "Lina의 오프라인 모험 시간이 모두 소진되었습니다.",
                AppLanguage.ENGLISH,
            ),
        )
        assertEquals(
            "Linaのオフライン冒険時間がすべて終了しました。",
            GameLocalization.translate(
                "Lina의 오프라인 모험 시간이 모두 소진되었습니다.",
                AppLanguage.JAPANESE,
            ),
        )
    }

    @Test
    fun `user nickname is preserved while surrounding authored text is translated`() {
        assertEquals(
            "알전사 · Fighter · Lv.11",
            GameLocalization.translatePreserving(
                text = "알전사 · 파이터 · Lv.11",
                language = AppLanguage.ENGLISH,
                protectedValues = listOf("알전사"),
            ),
        )
        assertEquals(
            "알전사 · ファイター · Lv.11",
            GameLocalization.translatePreserving(
                text = "알전사 · 파이터 · Lv.11",
                language = AppLanguage.JAPANESE,
                protectedValues = listOf("알전사"),
            ),
        )

        val story = "임전사는 로웬과 순찰대의 생존자를 구했다. " +
            "순찰대를 괴물에게 몰아넣은 자는 국경 안에 있었고, " +
            "로웬의 지도는 폐쇄된 우물을 가리켰다."
        assertEquals(
            "임전사 rescued Rowen and the patrol’s survivors. Whoever had driven the patrol " +
                "into the monsters was inside the border, and Rowen’s map pointed to a sealed well.",
            GameLocalization.translatePreserving(
                text = story,
                language = AppLanguage.ENGLISH,
                protectedValues = mapOf(
                    "임전사는" to "임전사",
                    "임전사" to "임전사",
                ),
            ),
        )
    }

    @Test
    fun `class equipment follows the approved class terminology`() {
        val equipmentNames = listOf(
            "파이터 투구" to ("Fighter Helm" to "ファイターの兜"),
            "시프 후드" to ("Thief Hood" to "シーフの頭巾"),
            "레인져 창" to ("Ranger Spear" to "レンジャーの槍"),
            "메이지 로브" to ("Mage Robe" to "メイジのローブ"),
            "클래릭 장화" to ("Cleric Boots" to "クレリックのブーツ"),
            "팔라딘 갑옷" to ("Paladin Armor" to "パラディンの鎧"),
        )
        equipmentNames.forEach { (korean, localized) ->
            assertEquals(localized.first, GameLocalization.translate(korean, AppLanguage.ENGLISH))
            assertEquals(localized.second, GameLocalization.translate(korean, AppLanguage.JAPANESE))
        }
    }
}
