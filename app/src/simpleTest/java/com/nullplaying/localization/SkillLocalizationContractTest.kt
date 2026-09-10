package com.nullplaying.localization

import android.app.Application
import com.nullplaying.engine.SkillCatalog
import com.nullplaying.model.HeroClass
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
@Config(sdk = [35], application = Application::class)
class SkillLocalizationContractTest {
    @Before
    fun setUp() {
        GameLocalization.initialize(RuntimeEnvironment.getApplication())
    }

    @Test
    fun `all one hundred twenty skill names reach English and Japanese without Korean leakage`() {
        assertEquals(120, SkillCatalog.all.size)
        HeroClass.entries.forEach { heroClass ->
            val definitions = SkillCatalog.forClass(heroClass)
            val english = definitions.map { GameLocalization.translate(it.name, AppLanguage.ENGLISH) }
            val japanese = definitions.map { GameLocalization.translate(it.name, AppLanguage.JAPANESE) }

            assertEquals(20, english.distinct().size)
            assertEquals(20, japanese.distinct().size)
            english.forEach { value ->
                assertFalse("$heroClass English leaked Korean: $value", KOREAN.containsMatchIn(value))
                assertTrue("$heroClass English has no Latin title text: $value", LATIN.containsMatchIn(value))
            }
            japanese.forEach { value ->
                assertFalse("$heroClass Japanese leaked Korean: $value", KOREAN.containsMatchIn(value))
                assertTrue("$heroClass Japanese has no Japanese title text: $value", JAPANESE.containsMatchIn(value))
            }
        }

        val allEnglish = SkillCatalog.all.map { GameLocalization.translate(it.name, AppLanguage.ENGLISH) }
        val allJapanese = SkillCatalog.all.map { GameLocalization.translate(it.name, AppLanguage.JAPANESE) }
        assertEquals("English skill names must remain globally distinct", 120, allEnglish.distinct().size)
        assertEquals("Japanese skill names must remain globally distinct", 120, allJapanese.distinct().size)
    }

    @Test
    fun `reviewed skill names preserve their combat meaning in English and Japanese`() {
        val reviewed = mapOf(
            "대지 가르기" to ("Earth Splitter" to "大地裂き"),
            "회오리 참격" to ("Whirlwind Slash" to "旋風斬り"),
            "섬광 일섬" to ("Flash Slash" to "閃光一閃"),
            "무극일섬" to ("Limitless Flash" to "無極一閃"),
            "빠른 찌르기" to ("Quick Thrust" to "素早い突き"),
            "독왕의 송곳니" to ("Venom King's Fang" to "毒王の牙"),
            "종말의 단검무" to ("Dagger Dance of Doom" to "終末の短剣舞"),
            "올가미 화살" to ("Snare Arrow" to "捕縛の矢"),
            "곰발톱 강타" to ("Bearclaw Strike" to "熊爪の強打"),
            "은하수 관통" to ("Galactic Pierce" to "天の川貫通"),
            "대기의 칼날" to ("Air Blade" to "大気の刃"),
            "성운 가르기" to ("Nebula Splitter" to "星雲裂き"),
            "삼중 낙뢰" to ("Triple Lightning Strike" to "三連落雷"),
            "사방 낙뢰" to ("Fourfold Lightning Strike" to "四方落雷"),
            "지옥불 구체" to ("Inferno Orb" to "業火の球"),
            "절대영도 파열" to ("Absolute Zero Burst" to "絶対零度破裂"),
            "뇌신의 사슬" to ("Thunder God's Chains" to "雷神の鎖"),
            "우주 종말" to ("Cosmic Apocalypse" to "宇宙の終焉"),
            "심판의 망치" to ("Hammer of Judgment" to "審判の槌"),
            "퇴마 연타" to ("Exorcism Barrage" to "退魔連打"),
            "퇴마 사슬" to ("Chains of Exorcism" to "退魔の鎖"),
            "파마의 인장" to ("Seal of Exorcism" to "破魔の印"),
            "천벌 분쇄" to ("Crushing Retribution" to "天罰粉砕"),
            "악마 파쇄진" to ("Demon-Crushing Circle" to "悪魔粉砕陣"),
            "세계수 성광포" to ("World Tree's Holy Cannon" to "世界樹の聖光砲"),
            "신좌의 망치" to ("Hammer of the Divine Throne" to "神座の槌"),
            "기사의 망치 강타" to ("Knight's Hammer Smash" to "騎士の槌撃"),
            "심판의 성추" to ("Holy Hammer of Judgment" to "審判の聖槌"),
            "십자 참격" to ("X-Slash" to "十文字斬り"),
            "십자 베기" to ("Cross Slash" to "十字斬り"),
            "별철 성추 강타" to ("Starsteel Holy Hammer Strike" to "星鉄の聖槌撃"),
            "심판왕의 망치" to ("Hammer of the Judgment King" to "審判王の槌"),
            "왕국 영겁참" to ("Kingdom's Eternal Slash" to "王国永劫斬"),
        )

        reviewed.forEach { (korean, expected) ->
            assertEquals(korean, expected.first, GameLocalization.translate(korean, AppLanguage.ENGLISH))
            assertEquals(korean, expected.second, GameLocalization.translate(korean, AppLanguage.JAPANESE))
        }
    }

    @Test
    fun `single and multi hit skill descriptions read naturally in English and Japanese`() {
        val singleHit = checkNotNull(SkillCatalog.find("warrior_t01_c01")).description
        val multiHit = checkNotNull(SkillCatalog.find("warrior_t13_c03")).description

        assertEquals(
            "Strike the enemy hard with Blade Slash.",
            GameLocalization.translate(singleHit, AppLanguage.ENGLISH),
        )
        assertEquals(
            "刃の斬撃で敵に強烈な一撃を与える。",
            GameLocalization.translate(singleHit, AppLanguage.JAPANESE),
        )
        assertEquals(
            "Hit the enemy 5 times in quick succession with Shadowless Flurry.",
            GameLocalization.translate(multiHit, AppLanguage.ENGLISH),
        )
        assertEquals(
            "無影連斬で敵を素早く5回攻撃する。",
            GameLocalization.translate(multiHit, AppLanguage.JAPANESE),
        )

        listOf(
            GameLocalization.translate(singleHit, AppLanguage.ENGLISH),
            GameLocalization.translate(singleHit, AppLanguage.JAPANESE),
            GameLocalization.translate(multiHit, AppLanguage.ENGLISH),
            GameLocalization.translate(multiHit, AppLanguage.JAPANESE),
        ).forEach { value ->
            assertFalse("Skill description leaked Korean: $value", KOREAN.containsMatchIn(value))
        }
    }

    companion object {
        private val KOREAN = Regex("[가-힣]")
        private val LATIN = Regex("[A-Za-z]")
        private val JAPANESE = Regex("[぀-ヿ㐀-鿿]")
    }
}
