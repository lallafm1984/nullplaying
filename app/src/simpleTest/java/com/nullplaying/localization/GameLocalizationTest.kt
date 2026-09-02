package com.nullplaying.localization

import android.content.Context
import com.nullplaying.engine.SimpleGameEngine
import com.nullplaying.model.HeroClass
import com.nullplaying.ui.localizedStoryText
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
@Config(sdk = [35], application = android.app.Application::class)
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
            "Privacy Policy",
            GameLocalization.translate("개인정보처리방침", AppLanguage.ENGLISH),
        )
        assertEquals(
            "プライバシーポリシー",
            GameLocalization.translate("개인정보처리방침", AppLanguage.JAPANESE),
        )
        assertEquals("Completed", GameLocalization.translate("완결", AppLanguage.ENGLISH))
        val classNames = listOf(
            "파이터" to ("Fighter" to "ファイター"),
            "시프" to ("Thief" to "シーフ"),
            "레인저" to ("Ranger" to "レンジャー"),
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
        assertEquals("View", GameLocalization.translate("보기", AppLanguage.ENGLISH))
        assertEquals("表示", GameLocalization.translate("보기", AppLanguage.JAPANESE))
        assertEquals(
            "No adventurer yet",
            GameLocalization.translate("아직 모험가가 없습니다", AppLanguage.ENGLISH),
        )
        assertEquals(
            "Choose a class",
            GameLocalization.translate("직업 선택", AppLanguage.ENGLISH),
        )
        assertEquals(
            "Start adventure with these stats",
            GameLocalization.translate("이 능력치로 모험 시작", AppLanguage.ENGLISH),
        )
        assertEquals(
            "Book 1 · Ashen Frontier",
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
    fun `ad recovery and optional charging copy is complete in English and Japanese`() {
        val readyMessage =
            "오프라인 모험 시간은 앱을 켜 둔 동안 자동으로 충전됩니다. " +
                "광고 시청은 선택 사항입니다. " +
                "광고를 끝까지 보면 즉시 가득 충전됩니다."

        assertEquals(
            "Retry ad setup",
            GameLocalization.translate("광고 설정 다시 연결", AppLanguage.ENGLISH),
        )
        assertEquals(
            "広告設定を再接続",
            GameLocalization.translate("광고 설정 다시 연결", AppLanguage.JAPANESE),
        )
        assertEquals(
            "Offline adventure time recharges automatically while the app is open. " +
                "Watching an ad is optional. " +
                "Watching an ad to the end instantly refills it.",
            GameLocalization.translate(readyMessage, AppLanguage.ENGLISH),
        )
        assertEquals(
            "オフライン冒険時間は、アプリを開いている間に自動で回復します。" +
                "広告の視聴は任意です。" +
                "広告を最後まで見ると、すぐに全回復します。",
            GameLocalization.translate(readyMessage, AppLanguage.JAPANESE),
        )
        assertEquals(
            "Offline adventure time is recharging.",
            GameLocalization.translate("오프라인 모험 시간을 충전 중입니다.", AppLanguage.ENGLISH),
        )
        assertEquals(
            "オフライン冒険時間を回復中です。",
            GameLocalization.translate("오프라인 모험 시간을 충전 중입니다.", AppLanguage.JAPANESE),
        )
    }

    @Test
    fun `prologue accessibility scene labels avoid invalid English ordinals`() {
        for (scene in 1..3) {
            val source = "프롤로그 ${scene}번째 장면"
            assertEquals("Prologue Scene $scene", GameLocalization.translate(source, AppLanguage.ENGLISH))
            assertEquals("プロローグ ${scene}話", GameLocalization.translate(source, AppLanguage.JAPANESE))
            assertEquals(source, GameLocalization.translate(source, AppLanguage.KOREAN))
        }
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
    fun `opening narrative replaces Korean hero particles with natural Japanese grammar`() {
        val localized = localizedStoryText(
            text = "QA는 무너진 북문 앞에서 손잡이가 닳은 검을 발견했다.",
            heroName = "QA",
            language = AppLanguage.JAPANESE,
        )

        assertEquals(
            "QAは崩れた北門の前で、柄のすり減った剣を見つけた。",
            localized,
        )
        assertFalse(Regex("[가-힣]").containsMatchIn(localized))
    }

    @Test
    fun `every class opening slide is fully localized in Japanese`() {
        val engine = SimpleGameEngine()

        HeroClass.entries.forEachIndexed { index, heroClass ->
            val seed = 100L + index
            val state = engine.newGame(
                name = "QA",
                heroClass = heroClass,
                rolledStats = engine.rollStats(seed, heroClass).stats,
                seed = seed,
                now = 1_000L,
            )

            state.adventureTale.openingSlides.forEach { slide ->
                val localized = localizedStoryText(
                    text = slide,
                    heroName = state.hero.name,
                    language = AppLanguage.JAPANESE,
                )
                assertFalse(
                    "${heroClass.name} opening slide leaked Korean: $localized",
                    Regex("[가-힣]").containsMatchIn(localized),
                )
            }
        }
    }

    @Test
    fun `class equipment follows the approved class terminology`() {
        val equipmentNames = listOf(
            "파이터 투구" to ("Fighter Helm" to "ファイターの兜"),
            "시프 후드" to ("Thief Hood" to "シーフの頭巾"),
            "레인저 창" to ("Ranger Spear" to "レンジャーの槍"),
            "메이지 로브" to ("Mage Robe" to "メイジのローブ"),
            "클래릭 장화" to ("Cleric Boots" to "クレリックのブーツ"),
            "팔라딘 갑옷" to ("Paladin Armor" to "パラディンの鎧"),
        )
        equipmentNames.forEach { (korean, localized) ->
            assertEquals(localized.first, GameLocalization.translate(korean, AppLanguage.ENGLISH))
            assertEquals(localized.second, GameLocalization.translate(korean, AppLanguage.JAPANESE))
        }
    }

    @Test
    fun `dynamic quest trophies use natural target language word order`() {
        assertEquals(
            "Behind-the-Barracks Spider Nest Debris",
            GameLocalization.translate("막사 뒷거미의 둥지 부스러기", AppLanguage.ENGLISH),
        )
        assertEquals(
            "Ember Bat Footprint Cast",
            GameLocalization.translate("잔불 박쥐의 발자국 석고", AppLanguage.ENGLISH),
        )
        assertEquals(
            "兵舎裏のクモの巣材",
            GameLocalization.translate("막사 뒷거미의 둥지 부스러기", AppLanguage.JAPANESE),
        )
        assertEquals(
            "Rampart Fox Specimen",
            GameLocalization.translate("성벽 여우 표본", AppLanguage.ENGLISH),
        )
        assertEquals(
            "城壁のキツネの標本",
            GameLocalization.translate("성벽 여우 표본", AppLanguage.JAPANESE),
        )
        assertEquals(
            "Field Notes on Sooty Horned Rabbit",
            GameLocalization.translate("검댕 뿔토끼의 활동 기록", AppLanguage.ENGLISH),
        )
        assertEquals(
            "煤けた角ウサギの行動記録",
            GameLocalization.translate("검댕 뿔토끼의 활동 기록", AppLanguage.JAPANESE),
        )
    }

    @Test
    fun `quest prose preserves the still warm lamp meaning`() {
        val source = "동이 텄는데 순찰대의 침상은 차갑고, 식지 않은 등불만 주인을 기다렸다."

        assertEquals(
            "Dawn had broken, yet the patrol's beds were cold. " +
                "Only a lamp still warm awaited its master.",
            GameLocalization.translate(source, AppLanguage.ENGLISH),
        )
        assertEquals(
            "夜は明けていたが、巡回隊の寝台は冷たかった。" +
                "まだ温もりの残る灯だけが、主の帰りを待っていた。",
            GameLocalization.translate(source, AppLanguage.JAPANESE),
        )
    }

    @Test
    fun `first chapter quest recap reads naturally in English and Japanese`() {
        assertEquals(
            "The evidence revealed when the soldiers were taken from the empty barracks and how many were missing.",
            GameLocalization.translate(
                "빈 막사에서 끌려간 때와 인원을 확인했다.",
                AppLanguage.ENGLISH,
            ),
        )
        assertEquals(
            "兵士たちが無人の兵舎から連れ去られた時刻と、行方不明者の数を突き止めた。",
            GameLocalization.translate(
                "빈 막사에서 끌려간 때와 인원을 확인했다.",
                AppLanguage.JAPANESE,
            ),
        )
        assertEquals(
            "Footprints leading out from within the fortress confirmed that someone on the inside was involved.",
            GameLocalization.translate(
                "안쪽 발자국에서 내부자의 개입을 확인했다.",
                AppLanguage.ENGLISH,
            ),
        )
        assertEquals(
            "砦の内側から外へ続く足跡により、内通者の関与が判明した。",
            GameLocalization.translate(
                "안쪽 발자국에서 내부자의 개입을 확인했다.",
                AppLanguage.JAPANESE,
            ),
        )
    }
}
