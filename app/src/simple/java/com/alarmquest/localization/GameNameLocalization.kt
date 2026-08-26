package com.alarmquest.localization

import com.alarmquest.engine.ClassEquipmentCatalog
import com.alarmquest.engine.MonsterModifierCompatibility
import com.alarmquest.engine.SimpleContent

/** Language-aware composition for names assembled from Korean gameplay data at runtime. */
internal object GameNameLocalization {
    fun equipmentName(source: String, language: AppLanguage): String {
        if (language == AppLanguage.KOREAN) return source
        val parts = SimpleContent.parseEquipmentName(source)
            ?: return GameLocalization.translate(source, language)
        val dropPrefix = shouldDropEquipmentPrefix(parts.prefix, parts.progressionLabel)
        val suffix = parts.enhancement?.let { " +$it" }.orEmpty()
        return when (language) {
            AppLanguage.ENGLISH -> listOfNotNull(
                parts.prefix?.takeUnless { dropPrefix }?.let(ENGLISH_EQUIPMENT_PREFIXES::get),
                englishProgressionLabel(parts.progressionLabel),
                GameLocalization.translate(parts.archetype, language),
            ).joinToString(" ") + suffix

            AppLanguage.JAPANESE -> buildString {
                parts.prefix
                    ?.takeUnless { dropPrefix }
                    ?.let(JAPANESE_EQUIPMENT_PREFIXES::get)
                    ?.let(::append)
                append(japaneseProgressionLabel(parts.progressionLabel))
                append(
                    JAPANESE_EQUIPMENT_BASE_OVERRIDES[parts.archetype]
                        ?: GameLocalization.translate(parts.archetype, language),
                )
                append(suffix)
            }

            AppLanguage.KOREAN -> source
        }
    }

    fun itemName(source: String, language: AppLanguage): String {
        if (language == AppLanguage.KOREAN) return source
        SimpleContent.parseEquipmentName(source)?.let { return equipmentName(source, language) }
        val parts = SimpleContent.parseGenericLootName(source)
            ?: return GameLocalization.translate(source, language)
        return when (language) {
            AppLanguage.ENGLISH -> englishLootName(
                ENGLISH_LOOT_MATERIALS.getValue(parts.material),
                parts.form,
            )

            AppLanguage.JAPANESE -> japaneseLootName(
                JAPANESE_LOOT_MATERIALS.getValue(parts.material),
                parts.form,
            )

            AppLanguage.KOREAN -> source
        }
    }

    fun monsterName(
        sourceName: String,
        sourceBaseName: String,
        language: AppLanguage,
    ): String {
        if (sourceBaseName.isBlank() || !sourceName.endsWith(sourceBaseName)) {
            return GameLocalization.translate(sourceName, language)
        }
        val sourceModifier = sourceName.removeSuffix(sourceBaseName).trim()
        if (
            sourceModifier.isNotBlank() &&
            !MonsterModifierCompatibility.isCompatible(sourceBaseName, sourceModifier)
        ) {
            return if (language == AppLanguage.KOREAN) {
                sourceBaseName
            } else {
                GameLocalization.translate(sourceBaseName, language)
            }
        }
        if (language == AppLanguage.KOREAN) return sourceName
        val localizedBase = when (language) {
            AppLanguage.ENGLISH -> ENGLISH_MONSTER_BASE_OVERRIDES[sourceBaseName]
            AppLanguage.JAPANESE -> JAPANESE_MONSTER_BASE_OVERRIDES[sourceBaseName]
            AppLanguage.KOREAN -> null
        } ?: GameLocalization.translate(sourceBaseName, language)
        if (sourceModifier.isBlank()) return localizedBase
        val localizedModifier = when (language) {
            AppLanguage.ENGLISH -> ENGLISH_ATTRIBUTIVE_MONSTER_MODIFIERS[sourceModifier]
            AppLanguage.JAPANESE -> JAPANESE_ATTRIBUTIVE_MONSTER_MODIFIERS[sourceModifier]
            AppLanguage.KOREAN -> null
        } ?: GameLocalization.translate(sourceModifier, language)
        return when (language) {
            AppLanguage.ENGLISH -> if (sourceModifier in ENGLISH_ATTRIBUTIVE_MONSTER_MODIFIERS) {
                "$localizedModifier $localizedBase"
            } else {
                "$localizedBase — $localizedModifier"
            }

            AppLanguage.JAPANESE -> "$localizedModifier$localizedBase"
            AppLanguage.KOREAN -> sourceName
        }
    }

    fun validationErrors(): List<String> = buildList {
        val prefixes = SimpleContent.localizableEquipmentPrefixes
        val progressionLabels = ClassEquipmentCatalog.localizableProgressionLabels
        val materials = SimpleContent.lootMaterials.toSet()
        if (prefixes != ENGLISH_EQUIPMENT_PREFIXES.keys) add("English equipment prefixes")
        if (prefixes != JAPANESE_EQUIPMENT_PREFIXES.keys) add("Japanese equipment prefixes")
        if (progressionLabels != ENGLISH_PROGRESSION_LABELS.keys) add("English progression labels")
        if (progressionLabels != JAPANESE_PROGRESSION_LABELS.keys) add("Japanese progression labels")
        if (materials != ENGLISH_LOOT_MATERIALS.keys) add("English loot materials")
        if (materials != JAPANESE_LOOT_MATERIALS.keys) add("Japanese loot materials")
        if (SimpleContent.monsterKinds.toSet() != ENGLISH_MONSTER_BASE_OVERRIDES.keys) {
            add("English monster bases")
        }
        if (SimpleContent.monsterKinds.toSet() != JAPANESE_MONSTER_BASE_OVERRIDES.keys) {
            add("Japanese monster bases")
        }
        if (!ENGLISH_ATTRIBUTIVE_MONSTER_MODIFIERS.keys.containsAll(SimpleContent.monsterAdjectives)) {
            add("English monster modifiers")
        }
        if (!JAPANESE_ATTRIBUTIVE_MONSTER_MODIFIERS.keys.containsAll(SimpleContent.monsterAdjectives)) {
            add("Japanese monster modifiers")
        }
    }

    private fun shouldDropEquipmentPrefix(
        prefix: String?,
        progressionLabel: String,
    ): Boolean = prefix == null ||
        prefix to progressionLabel in REDUNDANT_EQUIPMENT_TERMS

    private fun englishProgressionLabel(source: String): String =
        ENGLISH_PROGRESSION_LABELS[source]
            ?: TRANSCENDENT_PROGRESSION.matchEntire(source)?.groupValues?.get(1)?.let {
                "Transcendent Tier $it"
            }
            ?: GameLocalization.translate(source, AppLanguage.ENGLISH)

    private fun japaneseProgressionLabel(source: String): String =
        JAPANESE_PROGRESSION_LABELS[source]
            ?: TRANSCENDENT_PROGRESSION.matchEntire(source)?.groupValues?.get(1)?.let {
                "超越${it}段"
            }
            ?: GameLocalization.translate(source, AppLanguage.JAPANESE)

    private fun englishLootName(material: String, form: String): String = when (form) {
        "표본" -> "$material Specimen"
        "부적" -> "$material Charm"
        "결정" -> if (material.endsWith("Crystal")) material else "$material Crystal"
        "꾸러미" -> "Bundle of $material"
        "유물" -> "$material Relic"
        "조각" -> if (material.endsWith("Shard") || material.endsWith("Fragment")) {
            material
        } else {
            "$material Fragment"
        }
        "전리품" -> "$material Trophy"
        "밀봉함" -> "Sealed Cache of $material"
        "정수" -> "Essence of $material"
        "고서" -> "Tome of $material"
        "파편함" -> "Shard Case of $material"
        "문장" -> if (material.endsWith("Sigil")) material else "$material Crest"
        "핵" -> if (material.endsWith("Core")) material else "$material Core"
        "가루병" -> if (material in POWDER_LIKE_MATERIALS) {
            "Vial of $material"
        } else {
            "Vial of Powdered $material"
        }
        "장식품" -> "$material Ornament"
        "기념물" -> "$material Memento"
        else -> "$material ${GameLocalization.translate(form, AppLanguage.ENGLISH)}"
    }

    private fun japaneseLootName(material: String, form: String): String = when (form) {
        "표본" -> "${material}の標本"
        "부적" -> "${material}の護符"
        "결정" -> if (material.endsWith("結晶")) material else "${material}の結晶"
        "꾸러미" -> "${material}の包み"
        "유물" -> "${material}の遺物"
        "조각" -> if (material.endsWith("欠片") || material.endsWith("片")) material else "${material}の欠片"
        "전리품" -> "${material}の戦利品"
        "밀봉함" -> "${material}の封印箱"
        "정수" -> "${material}の精髄"
        "고서" -> "${material}の古書"
        "파편함" -> "${material}の欠片箱"
        "문장" -> if (material.endsWith("印章")) material else "${material}の紋章"
        "핵" -> if (material.endsWith("核")) material else "${material}の核"
        "가루병" -> "${material}の粉末瓶"
        "장식품" -> "${material}の装飾品"
        "기념물" -> "${material}の記念品"
        else -> material + GameLocalization.translate(form, AppLanguage.JAPANESE)
    }

    private val TRANSCENDENT_PROGRESSION = Regex("초월 ([1-9][0-9]*)단식")

    private val REDUNDANT_EQUIPMENT_TERMS = setOf(
        "수습생의" to "견습식",
        "여행자의" to "모험식",
        "왕가의" to "왕실제",
        "별빛" to "별철",
        "신성한" to "성역식",
        "태고의" to "태고식",
        "운명의" to "운명식",
    )

    private val ENGLISH_EQUIPMENT_PREFIXES = mapOf(
        "낡은" to "Worn",
        "거친" to "Rough",
        "소박한" to "Plain",
        "수습생의" to "Apprentice's",
        "여행자의" to "Traveler's",
        "손질한" to "Well-Kept",
        "튼튼한" to "Sturdy",
        "빛바랜" to "Faded",
        "단단한" to "Reinforced",
        "예리한" to "Keen",
        "정교한" to "Finely Crafted",
        "용병의" to "Mercenary's",
        "경비대의" to "Guard's",
        "철빛" to "Iron-Hued",
        "잘 벼린" to "Tempered",
        "든든한" to "Stalwart",
        "수호자의" to "Guardian's",
        "용맹한" to "Valiant",
        "왕가의" to "Royal",
        "폭풍의" to "Stormforged",
        "서리" to "Frost",
        "화염" to "Flame",
        "그림자" to "Shadow",
        "핏빛" to "Bloodstained",
        "미스릴" to "Mithril",
        "용비늘" to "Dragonscale",
        "별빛" to "Starlit",
        "신성한" to "Sacred",
        "새벽의" to "Dawnforged",
        "황혼의" to "Twilight",
        "월광" to "Moonlit",
        "정령의" to "Spiritbound",
        "고대의" to "Ancient",
        "심연의" to "Abyssal",
        "잊힌" to "Forgotten",
        "천상의" to "Celestial",
        "태고의" to "Primordial",
        "운명의" to "Fatebound",
        "세계수의" to "World-Tree",
        "성좌의" to "Constellation",
    )

    private val JAPANESE_EQUIPMENT_PREFIXES = mapOf(
        "낡은" to "古びた",
        "거친" to "粗製の",
        "소박한" to "質素な",
        "수습생의" to "見習いの",
        "여행자의" to "旅人の",
        "손질한" to "手入れの行き届いた",
        "튼튼한" to "丈夫な",
        "빛바랜" to "色あせた",
        "단단한" to "堅牢な",
        "예리한" to "鋭利な",
        "정교한" to "精巧な",
        "용병의" to "傭兵の",
        "경비대의" to "衛兵の",
        "철빛" to "鉄色の",
        "잘 벼린" to "鍛え上げた",
        "든든한" to "頼れる",
        "수호자의" to "守護者の",
        "용맹한" to "勇猛な",
        "왕가의" to "王家の",
        "폭풍의" to "嵐を宿す",
        "서리" to "霜の",
        "화염" to "炎の",
        "그림자" to "影の",
        "핏빛" to "血染めの",
        "미스릴" to "ミスリル",
        "용비늘" to "竜鱗の",
        "별빛" to "星明かりの",
        "신성한" to "聖なる",
        "새벽의" to "暁の",
        "황혼의" to "黄昏の",
        "월광" to "月光の",
        "정령의" to "精霊の",
        "고대의" to "古代の",
        "심연의" to "深淵の",
        "잊힌" to "忘れられた",
        "천상의" to "天上の",
        "태고의" to "太古の",
        "운명의" to "運命の",
        "세계수의" to "世界樹の",
        "성좌의" to "星座の",
    )

    private val ENGLISH_PROGRESSION_LABELS = mapOf(
        "훈련식" to "Training",
        "견습식" to "Apprentice",
        "모험식" to "Adventurer-Issue",
        "철제" to "Iron",
        "강화 철제" to "Reinforced Iron",
        "정련 철제" to "Tempered Iron",
        "정규군식" to "Military-Issue",
        "개량 전투식" to "Improved",
        "숙련 전투식" to "Veteran",
        "강철" to "Steel",
        "정련 강철" to "Tempered Steel",
        "기사단식" to "Order-Issue",
        "룬각인" to "Runed",
        "왕실제" to "Royal",
        "비전 강화" to "Arcane-Empowered",
        "명장제" to "Masterwork",
        "정예 기사식" to "Elite-Knight",
        "대가식" to "Grandmaster",
        "전승식" to "Heirloom",
        "은빛 합금" to "Silver Alloy",
        "별철" to "Starsteel",
        "용골 강화" to "Dragonbone-Reinforced",
        "성유 각인" to "Consecrated",
        "마정석" to "Mana Crystal",
        "영혼결정" to "Soul Crystal",
        "태양각인" to "Sunforged",
        "달각인" to "Moonforged",
        "성역식" to "Sanctified",
        "유산급" to "Relic",
        "심층제" to "Deepforged",
        "천공식" to "Skyforged",
        "태고식" to "Primordial",
        "운명식" to "Fatebound",
        "초월식" to "Transcendent",
    )

    private val JAPANESE_PROGRESSION_LABELS = mapOf(
        "훈련식" to "訓練用",
        "견습식" to "見習い用",
        "모험식" to "冒険者用",
        "철제" to "鉄製",
        "강화 철제" to "強化鉄製",
        "정련 철제" to "精錬鉄製",
        "정규군식" to "正規軍制式",
        "개량 전투식" to "改良戦闘型",
        "숙련 전투식" to "熟練兵仕様",
        "강철" to "鋼製",
        "정련 강철" to "精錬鋼製",
        "기사단식" to "騎士団制式",
        "룬각인" to "ルーン刻印",
        "왕실제" to "王室製",
        "비전 강화" to "秘術強化",
        "명장제" to "名匠作",
        "정예 기사식" to "精鋭騎士仕様",
        "대가식" to "達人作",
        "전승식" to "伝承",
        "은빛 합금" to "銀合金製",
        "별철" to "星鋼製",
        "용골 강화" to "竜骨強化",
        "성유 각인" to "聖油刻印",
        "마정석" to "魔晶石製",
        "영혼결정" to "魂晶製",
        "태양각인" to "太陽刻印",
        "달각인" to "月刻印",
        "성역식" to "聖域仕様",
        "유산급" to "遺産級",
        "심층제" to "深層製",
        "천공식" to "天空製",
        "태고식" to "太古",
        "운명식" to "運命",
        "초월식" to "超越",
    )

    private val JAPANESE_EQUIPMENT_BASE_OVERRIDES = mapOf(
        "파이터 투구" to "ファイターヘルム",
        "파이터 갑옷" to "ファイターアーマー",
        "파이터 건틀릿" to "ファイターガントレット",
        "파이터 장화" to "ファイターブーツ",
        "파이터 허리띠" to "ファイターベルト",
        "시프 후드" to "シーフフード",
        "시프 가죽옷" to "シーフレザー",
        "시프 장갑" to "シーフグローブ",
        "시프 장화" to "シーフブーツ",
        "시프 인장" to "シーフシジル",
        "레인져 창" to "レンジャースピア",
        "레인져 두건" to "レンジャーフード",
        "레인져 사냥복" to "レンジャーギア",
        "메이지 관" to "メイジサークレット",
        "메이지 로브" to "メイジローブ",
        "메이지 신발" to "メイジシューズ",
        "클래릭 관" to "クレリックサークレット",
        "클래릭 로브" to "クレリックローブ",
        "클래릭 완갑" to "クレリックヴァンブレイス",
        "클래릭 장화" to "クレリックブーツ",
        "클래릭 허리띠" to "クレリックベルト",
        "팔라딘 투구" to "パラディンヘルム",
        "팔라딘 갑옷" to "パラディンアーマー",
        "팔라딘 건틀릿" to "パラディンガントレット",
        "팔라딘 장화" to "パラディンブーツ",
        "팔라딘 허리띠" to "パラディンベルト",
    )

    private val ENGLISH_LOOT_MATERIALS = mapOf(
        "뿔늑대 송곳니" to "Horned Wolf Fang",
        "수정 파편" to "Crystal Shard",
        "검은 깃털" to "Black Feather",
        "별가루" to "Stardust",
        "용암 핵" to "Magma Core",
        "은빛 비늘" to "Silver Scale",
        "고대 톱니" to "Ancient Gear",
        "유령 천" to "Spectral Cloth",
        "거미 독낭" to "Spider Venom Sac",
        "그리핀 발톱" to "Griffin Talon",
        "정령 잔불" to "Spirit Ember",
        "푸른 수액" to "Azure Sap",
        "황혼 진주" to "Twilight Pearl",
        "미스릴 조각" to "Mithril Fragment",
        "봉인석" to "Sealing Stone",
        "와이번 가죽" to "Wyvern Hide",
        "심연의 눈" to "Abyssal Eye",
        "골렘 심장" to "Golem Heart",
        "달빛 버섯" to "Moonlit Mushroom",
        "왕가의 인장" to "Royal Sigil",
        "트롤 어금니" to "Troll Tusk",
        "밴시의 눈물" to "Banshee Tear",
        "천둥 결정" to "Thunder Crystal",
        "붉은 모래" to "Crimson Sand",
    )

    private val JAPANESE_LOOT_MATERIALS = mapOf(
        "뿔늑대 송곳니" to "角狼の牙",
        "수정 파편" to "水晶の欠片",
        "검은 깃털" to "黒羽",
        "별가루" to "星屑",
        "용암 핵" to "溶岩核",
        "은빛 비늘" to "銀鱗",
        "고대 톱니" to "古代の歯車",
        "유령 천" to "霊布",
        "거미 독낭" to "クモの毒袋",
        "그리핀 발톱" to "グリフォンの鉤爪",
        "정령 잔불" to "精霊の残り火",
        "푸른 수액" to "青い樹液",
        "황혼 진주" to "黄昏の真珠",
        "미스릴 조각" to "ミスリル片",
        "봉인석" to "封印石",
        "와이번 가죽" to "ワイバーンの皮",
        "심연의 눈" to "深淵の眼",
        "골렘 심장" to "ゴーレムの心臓",
        "달빛 버섯" to "月光キノコ",
        "왕가의 인장" to "王家の印章",
        "트롤 어금니" to "トロールの牙",
        "밴시의 눈물" to "バンシーの涙",
        "천둥 결정" to "雷の結晶",
        "붉은 모래" to "赤砂",
    )

    private val POWDER_LIKE_MATERIALS = setOf("Stardust", "Crimson Sand")

    private val ENGLISH_MONSTER_BASE_OVERRIDES = mapOf(
        "뿔늑대" to "Horned Wolf",
        "동굴 거미" to "Cave Spider",
        "갑주 멧돼지" to "Armored Boar",
        "잿빛 슬라임" to "Ash Slime",
        "숲 고블린" to "Forest Goblin",
        "돌가죽 트롤" to "Stonehide Troll",
        "해골 기사" to "Skeleton Knight",
        "그림자 박쥐" to "Shadow Bat",
        "늪 히드라" to "Marsh Hydra",
        "서리 와이번" to "Frost Wyvern",
        "불꽃 정령" to "Flame Spirit",
        "고대 골렘" to "Ancient Golem",
        "심연 사냥개" to "Abyss Hound",
        "독안개 만티코어" to "Venom-Mist Manticore",
        "모래 리자드맨" to "Sand Lizardman",
        "광산 코볼트" to "Mine Kobold",
        "황혼 밴시" to "Twilight Banshee",
        "천둥 그리핀" to "Thunder Griffin",
        "혈월 오우거" to "Bloodmoon Ogre",
        "공허 드레이크" to "Void Drake",
    )

    private val JAPANESE_MONSTER_BASE_OVERRIDES = mapOf(
        "뿔늑대" to "角狼",
        "동굴 거미" to "洞窟グモ",
        "갑주 멧돼지" to "装甲イノシシ",
        "잿빛 슬라임" to "灰色スライム",
        "숲 고블린" to "森ゴブリン",
        "돌가죽 트롤" to "石肌トロール",
        "해골 기사" to "骸骨騎士",
        "그림자 박쥐" to "影コウモリ",
        "늪 히드라" to "沼地ヒドラ",
        "서리 와이번" to "フロストワイバーン",
        "불꽃 정령" to "炎の精霊",
        "고대 골렘" to "古代ゴーレム",
        "심연 사냥개" to "深淵の猟犬",
        "독안개 만티코어" to "毒霧マンティコア",
        "모래 리자드맨" to "砂漠リザードマン",
        "광산 코볼트" to "鉱山コボルト",
        "황혼 밴시" to "黄昏のバンシー",
        "천둥 그리핀" to "雷霆グリフォン",
        "혈월 오우거" to "血月オーガ",
        "공허 드레이크" to "ヴォイドドレイク",
    )

    private val ENGLISH_ATTRIBUTIVE_MONSTER_MODIFIERS = mapOf(
        "굶주린" to "Hungry",
        "잿빛" to "Ashen",
        "광포한" to "Frenzied",
        "상처 입은" to "Wounded",
        "검은갈기" to "Black-Maned",
        "핏빛" to "Bloodstained",
        "고대의" to "Ancient",
        "폭풍을 두른" to "Storm-Wreathed",
        "달빛에 물든" to "Moonlit",
        "저주받은" to "Cursed",
        "강철발톱" to "Steel-Clawed",
        "안개 속" to "Mist-Shrouded",
        "수정 껍질의" to "Crystal-Shelled",
        "별을 삼킨" to "Star-Devouring",
        "불길한" to "Ominous",
        "길 잃은" to "Lost",
        "깨어난" to "Awakened",
        "침묵의" to "Silent",
        "배고픈" to "Hungry",
        "날카로운" to "Sharp",
        "낮게 웅크린" to "Low-Crouching",
        "눈 밝은" to "Keen-Eyed",
        "제멋대로인" to "Unruly",
        "숨을 죽인" to "Silent",
        "빠른" to "Swift",
        "노련한" to "Seasoned",
        "무리를 이탈한" to "Stray",
        "밤을 노리는" to "Night-Stalking",
        "한쪽 눈의" to "One-Eyed",
        "거친 털의" to "Rough-Coated",
        "소리 없는" to "Soundless",
        "흔적을 감춘" to "Trackless",
    )

    private val JAPANESE_ATTRIBUTIVE_MONSTER_MODIFIERS = mapOf(
        "굶주린" to "飢えた",
        "잿빛" to "灰色の",
        "광포한" to "狂暴な",
        "상처 입은" to "傷ついた",
        "검은갈기" to "黒鬣の",
        "핏빛" to "血染めの",
        "고대의" to "古代の",
        "폭풍을 두른" to "嵐をまとった",
        "달빛에 물든" to "月光に染まった",
        "저주받은" to "呪われた",
        "강철발톱" to "鋼爪の",
        "안개 속" to "霧に潜む",
        "수정 껍질의" to "水晶殻の",
        "별을 삼킨" to "星喰らいの",
        "불길한" to "不吉な",
        "길 잃은" to "道に迷った",
        "깨어난" to "目覚めた",
        "침묵의" to "沈黙の",
        "배고픈" to "腹を空かせた",
        "날카로운" to "鋭い",
        "낮게 웅크린" to "身を低くした",
        "눈 밝은" to "目ざとい",
        "제멋대로인" to "気ままな",
        "숨을 죽인" to "息を潜めた",
        "빠른" to "素早い",
        "노련한" to "手練れの",
        "무리를 이탈한" to "群れを離れた",
        "밤을 노리는" to "夜を狙う",
        "한쪽 눈의" to "片目の",
        "거친 털의" to "毛並みの荒い",
        "소리 없는" to "音なき",
        "흔적을 감춘" to "痕跡を隠した",
    )
}
