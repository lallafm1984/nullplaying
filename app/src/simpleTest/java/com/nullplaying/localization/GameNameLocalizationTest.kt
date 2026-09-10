package com.nullplaying.localization

import com.nullplaying.engine.ClassEquipmentCatalog
import com.nullplaying.engine.MonsterModifierCompatibility
import com.nullplaying.engine.QuestMonsterCatalog
import com.nullplaying.engine.SimpleContent
import com.nullplaying.model.EquipmentSlot
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
@Config(sdk = [35], application = android.app.Application::class)
class GameNameLocalizationTest {
    @Before
    fun setUp() {
        GameLocalization.initialize(RuntimeEnvironment.getApplication())
    }

    @Test
    fun `every runtime name component has reviewed English and Japanese terminology`() {
        assertEquals(emptyList<String>(), GameNameLocalization.validationErrors())
    }

    @Test
    fun `equipment names follow native game grammar and remove redundant identities`() {
        assertEquals(
            "Mercenary's Iron Mace +3",
            GameNameLocalization.equipmentName("용병의 철제 철퇴 +3", AppLanguage.ENGLISH),
        )
        assertEquals(
            "傭兵の鉄製メイス +3",
            GameNameLocalization.equipmentName("용병의 철제 철퇴 +3", AppLanguage.JAPANESE),
        )
        assertEquals(
            "Guardian's Iron Chain Helm +3",
            GameNameLocalization.equipmentName("수호자의 철제 사슬모자 +3", AppLanguage.ENGLISH),
        )
        assertEquals(
            "守護者の鉄製チェーンヘルム +3",
            GameNameLocalization.equipmentName("수호자의 철제 사슬모자 +3", AppLanguage.JAPANESE),
        )
        assertEquals(
            "Adventurer-Issue Fighter Armor",
            GameNameLocalization.equipmentName("여행자의 모험식 파이터 갑옷", AppLanguage.ENGLISH),
        )
        assertEquals(
            "冒険者用ファイターアーマー",
            GameNameLocalization.equipmentName("여행자의 모험식 파이터 갑옷", AppLanguage.JAPANESE),
        )
        assertEquals(
            "Fighter Training Boots",
            GameNameLocalization.equipmentName("훈련식 파이터 장화", AppLanguage.ENGLISH),
        )
        assertEquals(
            "Tempered Iron Longsword",
            GameNameLocalization.equipmentName("철빛 정련 철제 장검", AppLanguage.ENGLISH),
        )
        assertEquals(
            "精錬鉄製長剣",
            GameNameLocalization.equipmentName("철빛 정련 철제 장검", AppLanguage.JAPANESE),
        )
        assertEquals(
            "Finely Crafted Transcendent Tier 999 Priest's Silk Shoes +99",
            GameNameLocalization.equipmentName(
                "정교한 초월 999단식 신관 비단신 +99",
                AppLanguage.ENGLISH,
            ),
        )
        assertEquals(
            "精巧な超越999段神官の絹靴 +99",
            GameNameLocalization.equipmentName(
                "정교한 초월 999단식 신관 비단신 +99",
                AppLanguage.JAPANESE,
            ),
        )
        assertEquals(
            "傭兵の鉄製メイス +3",
            GameNameLocalization.equipmentName("Mercenary's Iron Mace +3", AppLanguage.JAPANESE),
        )
        assertEquals(
            "Finely Crafted Transcendent Tier 999 Priest's Silk Shoes +99",
            GameNameLocalization.equipmentName("精巧な超越999段神官の絹靴 +99", AppLanguage.ENGLISH),
        )
        assertEquals(
            "용병의 철제 철퇴 +3",
            GameNameLocalization.equipmentName("Mercenary's Iron Mace +3", AppLanguage.KOREAN),
        )
        assertEquals(
            "傭兵の鉄製メイス +3",
            GameNameLocalization.itemName("Mercenary's Iron Mace +3", AppLanguage.JAPANESE),
        )
        assertEquals(
            "용병의 철제 철퇴 +3",
            GameNameLocalization.itemName("Mercenary's Iron Mace +3", AppLanguage.KOREAN),
        )
    }

    @Test
    fun `generic item names use language-specific templates and collapse duplicate nouns`() {
        assertEquals(
            "Horned Wolf Fang Specimen",
            GameNameLocalization.itemName("뿔늑대 송곳니 표본", AppLanguage.ENGLISH),
        )
        assertEquals(
            "角狼の牙の標本",
            GameNameLocalization.itemName("뿔늑대 송곳니 표본", AppLanguage.JAPANESE),
        )
        assertEquals(
            "Thunder Crystal",
            GameNameLocalization.itemName("천둥 결정 결정", AppLanguage.ENGLISH),
        )
        assertEquals(
            "雷の結晶",
            GameNameLocalization.itemName("천둥 결정 결정", AppLanguage.JAPANESE),
        )
        assertEquals(
            "Cluster of Crystal Shards",
            GameNameLocalization.itemName("수정 파편 결정", AppLanguage.ENGLISH),
        )
        assertEquals(
            "水晶の欠片の集合晶",
            GameNameLocalization.itemName("수정 파편 결정", AppLanguage.JAPANESE),
        )
        assertEquals(
            "Royal Sigil",
            GameNameLocalization.itemName("왕가의 인장 문장", AppLanguage.ENGLISH),
        )
        assertEquals(
            "王家の印章",
            GameNameLocalization.itemName("왕가의 인장 문장", AppLanguage.JAPANESE),
        )
        assertEquals(
            "Vial of Stardust",
            GameNameLocalization.itemName("별가루 가루병", AppLanguage.ENGLISH),
        )
        assertEquals(
            "星屑入りの小瓶",
            GameNameLocalization.itemName("별가루 가루병", AppLanguage.JAPANESE),
        )
        assertEquals(
            "Case of Mithril Fragment",
            GameNameLocalization.itemName("미스릴 조각 파편함", AppLanguage.ENGLISH),
        )
        assertEquals(
            "ミスリル片入りの箱",
            GameNameLocalization.itemName("미스릴 조각 파편함", AppLanguage.JAPANESE),
        )
        assertEquals(
            "グリフォンの鉤爪の粉末入り小瓶",
            GameNameLocalization.itemName("그리핀 발톱 가루병", AppLanguage.JAPANESE),
        )
    }

    @Test
    fun `runtime composition removes repeated qualities and preserves gate boss framing`() {
        assertEquals(
            "Reinforced Iron Longsword +3",
            GameNameLocalization.equipmentName("단단한 강화 철제 장검 +3", AppLanguage.ENGLISH),
        )
        assertEquals(
            "Iron Faceguard +3",
            GameNameLocalization.equipmentName("철빛 철제 철면 +3", AppLanguage.ENGLISH),
        )
        assertEquals(
            "鉄製フェイスガード +3",
            GameNameLocalization.equipmentName("철빛 철제 철면 +3", AppLanguage.JAPANESE),
        )
        assertEquals(
            "Mana-Crystal Orb +3",
            GameNameLocalization.equipmentName("마정석 수정구 +3", AppLanguage.ENGLISH),
        )
        assertEquals(
            "Ancient Golem",
            GameNameLocalization.monsterName("고대의 고대 골렘", "고대 골렘", AppLanguage.ENGLISH),
        )
        assertEquals(
            "古代ゴーレム",
            GameNameLocalization.monsterName("고대의 고대 골렘", "고대 골렘", AppLanguage.JAPANESE),
        )
        assertEquals(
            "Sector 123 Gate Boss · Gatekeeper of the Sealed Waterway",
            GameNameLocalization.monsterName(
                "제123구역 관문지기 · 잠긴 수로의 문지기",
                "잠긴 수로의 문지기",
                AppLanguage.ENGLISH,
            ),
        )
        assertEquals(
            "第123区域の関門ボス · 封じられた水路の門番",
            GameNameLocalization.monsterName(
                "제123구역 관문지기 · 잠긴 수로의 문지기",
                "잠긴 수로의 문지기",
                AppLanguage.JAPANESE,
            ),
        )
        assertEquals(
            "Nest Debris from Mother of the Roadside Nest",
            GameNameLocalization.itemName("길목 둥지의 어미의 둥지 부스러기", AppLanguage.ENGLISH),
        )
    }

    @Test
    fun `monster adjectives lead nouns while longer epithets remain readable`() {
        assertEquals(
            "One-Eyed Loyal Hound",
            GameNameLocalization.monsterName(
                "한쪽 눈의 충성 사냥개",
                "충성 사냥개",
                AppLanguage.ENGLISH,
            ),
        )
        assertEquals(
            "片目の忠実猟犬",
            GameNameLocalization.monsterName(
                "한쪽 눈의 충성 사냥개",
                "충성 사냥개",
                AppLanguage.JAPANESE,
            ),
        )
        assertEquals(
            "Frenzied Frost Wyvern",
            GameNameLocalization.monsterName(
                "광포한 서리 와이번",
                "서리 와이번",
                AppLanguage.ENGLISH,
            ),
        )
        assertEquals(
            "Hungry Sooty Horned Rabbit",
            GameNameLocalization.monsterName(
                "배고픈 검댕 뿔토끼",
                "검댕 뿔토끼",
                AppLanguage.ENGLISH,
            ),
        )
        assertEquals(
            "Rough-Coated Loyal Hound",
            GameNameLocalization.monsterName(
                "거친 털의 충성 사냥개",
                "충성 사냥개",
                AppLanguage.ENGLISH,
            ),
        )
        assertEquals(
            "毛並みの荒い忠実猟犬",
            GameNameLocalization.monsterName(
                "거친 털의 충성 사냥개",
                "충성 사냥개",
                AppLanguage.JAPANESE,
            ),
        )
        assertEquals(
            "Paper Featherbird",
            GameNameLocalization.monsterName(
                "거친 털의 종이 깃털새",
                "종이 깃털새",
                AppLanguage.ENGLISH,
            ),
        )
        assertEquals(
            "紙の羽鳥",
            GameNameLocalization.monsterName(
                "거친 털의 종이 깃털새",
                "종이 깃털새",
                AppLanguage.JAPANESE,
            ),
        )
        val englishEpithet = GameNameLocalization.monsterName(
            "봉인을 난도질한 종이 깃털새",
            "종이 깃털새",
            AppLanguage.ENGLISH,
        )
        assertTrue(englishEpithet.contains(" — "))
        val japaneseEpithet = GameNameLocalization.monsterName(
            "봉인을 난도질한 종이 깃털새",
            "종이 깃털새",
            AppLanguage.JAPANESE,
        )
        assertFalse(japaneseEpithet.contains(' '))
    }

    @Test
    fun `authored monster compounds preserve their intended Korean meaning`() {
        assertEquals(
            "Smuggling-Route Field Mouse",
            GameNameLocalization.monsterName("밀수로 들쥐", "밀수로 들쥐", AppLanguage.ENGLISH),
        )
        assertEquals(
            "密輸路の野ネズミ",
            GameNameLocalization.monsterName("밀수로 들쥐", "밀수로 들쥐", AppLanguage.JAPANESE),
        )
        assertEquals(
            "Pit Fighter of the Chain Road",
            GameNameLocalization.monsterName("쇠사슬 길의 투장", "쇠사슬 길의 투장", AppLanguage.ENGLISH),
        )
        assertEquals(
            "鎖道の闘士",
            GameNameLocalization.monsterName("쇠사슬 길의 투장", "쇠사슬 길의 투장", AppLanguage.JAPANESE),
        )
        assertEquals(
            "Devourer of Birdsong",
            GameNameLocalization.monsterName("새 울음을 삼킨 자", "새 울음을 삼킨 자", AppLanguage.ENGLISH),
        )
        assertEquals(
            "鳥のさえずりを喰らう者",
            GameNameLocalization.monsterName("새 울음을 삼킨 자", "새 울음을 삼킨 자", AppLanguage.JAPANESE),
        )
        assertEquals(
            "Noon-Window Moth",
            GameNameLocalization.monsterName("정오창 나방", "정오창 나방", AppLanguage.ENGLISH),
        )
        assertEquals(
            "正午の窓の蛾",
            GameNameLocalization.monsterName("정오창 나방", "정오창 나방", AppLanguage.JAPANESE),
        )
        assertEquals(
            "Foundry Moth",
            GameNameLocalization.monsterName("주조장 나방", "주조장 나방", AppLanguage.ENGLISH),
        )
        assertEquals(
            "鋳造所の蛾",
            GameNameLocalization.monsterName("주조장 나방", "주조장 나방", AppLanguage.JAPANESE),
        )
        assertEquals(
            "Underground-Mirror Bat — Awakened by the Unpromised Fourth Light",
            GameNameLocalization.monsterName(
                "약속 없는 네 번째 빛에 깨어난 지하거울 박쥐",
                "지하거울 박쥐",
                AppLanguage.ENGLISH,
            ),
        )
        assertEquals(
            "約束なき第四の光で目覚めた地下鏡のコウモリ",
            GameNameLocalization.monsterName(
                "약속 없는 네 번째 빛에 깨어난 지하거울 박쥐",
                "지하거울 박쥐",
                AppLanguage.JAPANESE,
            ),
        )
    }

    @Test
    fun `all generated equipment and generic item combinations are fully localized`() {
        val equipmentSources = buildList {
            (1L..108L).forEach { level ->
                HeroClass.entries.forEach { heroClass ->
                    EquipmentSlot.entries.forEach { slot ->
                        repeat(ClassEquipmentCatalog.BASE_VARIANTS_PER_SLOT) { variant ->
                            val base = SimpleContent.equipmentBase(slot, level, heroClass, variant)
                            SimpleContent.equipmentPrefixes(level).forEach { prefix ->
                                add("$prefix $base +3")
                            }
                        }
                    }
                }
            }
        }
        val itemSources = SimpleContent.lootMaterials.flatMap { material ->
            SimpleContent.lootForms.map { form -> "$material $form" }
        }

        assertFullyLocalized(equipmentSources, GameNameLocalization::equipmentName)
        assertFullyLocalized(itemSources, GameNameLocalization::itemName)
        listOf(AppLanguage.ENGLISH, AppLanguage.JAPANESE).forEach { language ->
            equipmentSources.forEach { source ->
                assertNoAdjacentEnglishDuplicate(
                    source,
                    language,
                    GameNameLocalization.equipmentName(source, language),
                )
            }
        }
    }

    @Test
    fun `all generated monster combinations and quest trophies are fully localized`() {
        val monsterPairs = buildList {
            SimpleContent.monsterKinds.forEach { base ->
                add(base to base)
                SimpleContent.monsterAdjectives.forEach { modifier ->
                    val repaired = MonsterModifierCompatibility.repairedName(
                        sourceName = "$modifier $base",
                        baseName = base,
                        candidates = SimpleContent.monsterAdjectives,
                    )
                    add(repaired to base)
                }
            }
            QuestMonsterCatalog.groups.forEach { group ->
                (group.normals + group.elites + group.bosses).forEach { definition ->
                    add(definition.baseName to definition.baseName)
                }
                group.normals.forEach { definition ->
                    group.adjectives.forEach { modifier ->
                        val repaired = MonsterModifierCompatibility.repairedName(
                            sourceName = "$modifier ${definition.baseName}",
                            baseName = definition.baseName,
                            candidates = group.adjectives,
                        )
                        add(repaired to definition.baseName)
                    }
                }
            }
        }
        val trophyNames = QuestMonsterCatalog.groups
            .flatMap { it.normals + it.elites + it.bosses }
            .flatMap { it.trophyNames }

        listOf(AppLanguage.ENGLISH, AppLanguage.JAPANESE).forEach { language ->
            monsterPairs.forEach { (sourceName, sourceBase) ->
                val localized = GameNameLocalization.monsterName(sourceName, sourceBase, language)
                assertNoKorean(sourceName, language, localized)
                if (language == AppLanguage.JAPANESE) {
                    assertFalse("Japanese monster contains a space: $sourceName -> $localized", localized.contains(' '))
                }
                assertNoAdjacentEnglishDuplicate(sourceName, language, localized)
            }
            trophyNames.forEach { source ->
                val localized = GameNameLocalization.itemName(source, language)
                assertNoKorean(source, language, localized)
                if (language == AppLanguage.JAPANESE) {
                    assertFalse("Japanese trophy contains a space: $source -> $localized", localized.contains(' '))
                }
                assertNoAdjacentEnglishDuplicate(source, language, localized)
            }
        }
    }

    private fun assertNoAdjacentEnglishDuplicate(
        source: String,
        language: AppLanguage,
        localized: String,
    ) {
        if (language != AppLanguage.ENGLISH) return
        val words = ENGLISH_WORD.findAll(localized).map { it.value.lowercase() }.toList()
        assertFalse(
            "English name repeats adjacent words: $source -> $localized",
            words.zipWithNext().any { (first, second) -> first == second },
        )
    }

    private fun assertFullyLocalized(
        sources: Iterable<String>,
        localize: (String, AppLanguage) -> String,
    ) {
        listOf(AppLanguage.ENGLISH, AppLanguage.JAPANESE).forEach { language ->
            sources.forEach { source ->
                val localized = localize(source, language)
                assertNoKorean(source, language, localized)
                if (language == AppLanguage.JAPANESE) {
                    val withoutEnhancement = localized.removeSuffix(" +3")
                    assertFalse(
                        "Japanese name contains a space: $source -> $localized",
                        withoutEnhancement.contains(' '),
                    )
                }
            }
        }
    }

    private fun assertNoKorean(source: String, language: AppLanguage, localized: String) {
        assertFalse(
            "$language localization contains Korean: $source -> $localized",
            KOREAN_TEXT.containsMatchIn(localized),
        )
    }

    companion object {
        private val KOREAN_TEXT = Regex("[가-힣]")
        private val ENGLISH_WORD = Regex("[A-Za-z]+")
    }
}
