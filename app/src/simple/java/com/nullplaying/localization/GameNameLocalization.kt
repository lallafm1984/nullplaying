package com.nullplaying.localization

import com.nullplaying.engine.ClassEquipmentCatalog
import com.nullplaying.engine.MonsterModifierCompatibility
import com.nullplaying.engine.QuestMonsterCatalog
import com.nullplaying.engine.SimpleContent

/** Language-aware composition for names assembled from Korean gameplay data at runtime. */
internal object GameNameLocalization {
    fun equipmentName(source: String, language: AppLanguage): String {
        if (language == AppLanguage.KOREAN) return source
        val parts = SimpleContent.parseEquipmentName(source)
            ?: return GameLocalization.translate(source, language)
        val dropPrefix = shouldDropEquipmentPrefix(
            prefix = parts.prefix,
            progressionLabel = parts.progressionLabel,
            archetype = parts.archetype,
        )
        val suffix = parts.enhancement?.let { " +$it" }.orEmpty()
        return when (language) {
            AppLanguage.ENGLISH -> {
                val progression = englishProgressionLabel(parts.progressionLabel)
                val archetype = ENGLISH_EQUIPMENT_BASE_OVERRIDES[parts.archetype]
                    ?: GameLocalization.translate(parts.archetype, language)
                val core = englishEquipmentCore(progression, archetype)
                val prefix = parts.prefix
                    ?.takeUnless { dropPrefix }
                    ?.let(ENGLISH_EQUIPMENT_PREFIXES::get)
                    ?.takeUnless { localizedPrefix -> hasBoundaryOverlap(localizedPrefix, core) }
                listOfNotNull(
                    prefix,
                    core,
                ).joinToString(" ") + suffix
            }

            AppLanguage.JAPANESE -> buildString {
                parts.prefix
                    ?.takeUnless { dropPrefix }
                    ?.let(JAPANESE_EQUIPMENT_PREFIXES::get)
                    ?.let(::append)
                append(japaneseProgressionLabel(parts.progressionLabel))
                append(japaneseEquipmentBase(parts.archetype))
                append(suffix)
            }

            AppLanguage.KOREAN -> source
        }
    }

    fun itemName(source: String, language: AppLanguage): String {
        if (language == AppLanguage.KOREAN) return source
        SimpleContent.parseEquipmentName(source)?.let { return equipmentName(source, language) }
        questTrophyName(source, language)?.let { return it }
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
        if (language == AppLanguage.KOREAN) return sourceName
        LABYRINTH_GATE_MONSTER.matchEntire(sourceName)?.let { match ->
            val sector = match.groupValues[1]
            val encounterSource = match.groupValues[2]
            val localizedEncounter = monsterName(encounterSource, sourceBaseName, language)
            return when (language) {
                AppLanguage.ENGLISH -> "Sector $sector Gate Boss · $localizedEncounter"
                AppLanguage.JAPANESE -> "第${sector}区域の関門ボス · $localizedEncounter"
                AppLanguage.KOREAN -> sourceName
            }
        }
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
        val localizedBase = when (language) {
            AppLanguage.ENGLISH -> ENGLISH_MONSTER_BASE_OVERRIDES[sourceBaseName]
            AppLanguage.JAPANESE -> JAPANESE_MONSTER_BASE_OVERRIDES[sourceBaseName]
            AppLanguage.KOREAN -> null
        } ?: GameLocalization.translate(sourceBaseName, language)
        if (sourceModifier.isBlank()) return localizedBase
        if (sourceModifier to sourceBaseName in REDUNDANT_MONSTER_TERMS) return localizedBase
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
        if (ENGLISH_LOOT_MATERIALS.values.toSet() != ENGLISH_LOOT_QUANTITIES.keys) {
            add("English loot quantities")
        }
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
        archetype: String,
    ): Boolean = prefix == null ||
        prefix to progressionLabel in REDUNDANT_EQUIPMENT_TERMS ||
        REDUNDANT_EQUIPMENT_ARCHETYPE_PREFIXES[prefix].orEmpty().any(archetype::startsWith)

    private fun englishProgressionLabel(source: String): String =
        ENGLISH_PROGRESSION_LABELS[source]
            ?: TRANSCENDENT_PROGRESSION.matchEntire(source)?.groupValues?.get(1)?.let {
                "Transcendent Tier $it"
            }
            ?: GameLocalization.translate(source, AppLanguage.ENGLISH)

    private fun englishEquipmentCore(progression: String, archetype: String): String {
        val className = archetype.substringBefore(' ')
        val reordered = if (
            progression == "Training" &&
            className in ENGLISH_CLASS_NAMES &&
            archetype.length > className.length
        ) {
            "$className Training ${archetype.removePrefix(className).trimStart()}"
        } else {
            "$progression $archetype"
        }
        if (reordered != "$progression $archetype") return reordered
        val overlap = progression
            .split(' ', '-')
            .lastOrNull()
            ?.takeIf { lastProgressionWord ->
                archetype.substringBefore(' ').equals(lastProgressionWord, ignoreCase = true)
            }
            ?: return reordered
        return "$progression ${archetype.removePrefix(overlap).trimStart()}"
    }

    private fun japaneseProgressionLabel(source: String): String =
        JAPANESE_PROGRESSION_LABELS[source]
            ?: TRANSCENDENT_PROGRESSION.matchEntire(source)?.groupValues?.get(1)?.let {
                "超越${it}段"
            }
            ?: GameLocalization.translate(source, AppLanguage.JAPANESE)

    private fun japaneseEquipmentBase(source: String): String =
        JAPANESE_EQUIPMENT_BASE_OVERRIDES[source]
            ?: GameLocalization.translate(source, AppLanguage.JAPANESE)

    private fun hasBoundaryOverlap(prefix: String, core: String): Boolean =
        prefix.split(' ', '-').lastOrNull()?.let { lastPrefixWord ->
            core.substringBefore(' ').equals(lastPrefixWord, ignoreCase = true)
        } == true

    private fun questTrophyName(source: String, language: AppLanguage): String? {
        val parts = parseQuestTrophyName(source) ?: return null
        val localizedBase = GameLocalization.translate(parts.baseName, language)
        return when (language) {
            AppLanguage.ENGLISH -> when (parts.form) {
                "잔해" -> "Remains of $localizedBase"
                "표본" -> "$localizedBase Specimen"
                "흔적" -> "Traces of $localizedBase"
                "외피 조각" -> "Outer-Shell Fragment from $localizedBase"
                "조직 표본" -> "Tissue Sample from $localizedBase"
                "둥지 부스러기" -> "Nest Debris from $localizedBase"
                "발자국 석고" -> "Footprint Cast of $localizedBase"
                "먹이 흔적" -> "Feeding Traces of $localizedBase"
                "재가루" -> "Ash Residue from $localizedBase"
                "생태 기록" -> "Ecology Notes on $localizedBase"
                "활동 기록" -> "Activity Log for $localizedBase"
                "탈피 조각" -> "Shed Fragment from $localizedBase"
                "선혈 표본" -> "Fresh Blood Sample from $localizedBase"
                "음영 스케치" -> "Silhouette Sketch of $localizedBase"
                "이동로 표식" -> "Route Marker for $localizedBase"
                "관찰 일지" -> "Observation Log for $localizedBase"
                else -> return null
            }

            AppLanguage.JAPANESE -> localizedBase + when (parts.form) {
                "잔해" -> "の残骸"
                "표본" -> "の標本"
                "흔적" -> "の痕跡"
                "외피 조각" -> "の外皮片"
                "조직 표본" -> "の組織標本"
                "둥지 부스러기" -> "の巣材"
                "발자국 석고" -> "の足跡石膏型"
                "먹이 흔적" -> "の食痕"
                "재가루" -> "の灰"
                "생태 기록" -> "の生態記録"
                "활동 기록" -> "の行動記録"
                "탈피 조각" -> "の抜け殻"
                "선혈 표본" -> "の血液標本"
                "음영 스케치" -> "のシルエット画"
                "이동로 표식" -> "の移動経路標識"
                "관찰 일지" -> "の観察記録"
                else -> return null
            }

            AppLanguage.KOREAN -> source
        }
    }

    private fun parseQuestTrophyName(source: String): QuestTrophyNameParts? =
        QUEST_TROPHY_FORMS.firstNotNullOfOrNull { form ->
            val suffix = if (form == "표본") " $form" else "의 $form"
            if (!source.endsWith(suffix)) return@firstNotNullOfOrNull null
            val baseName = source.removeSuffix(suffix)
            baseName.takeIf(QUEST_MONSTER_BASE_NAMES::contains)?.let {
                QuestTrophyNameParts(baseName = it, form = form)
            }
        }

    private fun englishLootName(material: String, form: String): String = when (form) {
        "표본" -> "$material Specimen"
        "부적" -> "$material Charm"
        "결정" -> when {
            material.endsWith("Crystal") -> material
            material.endsWith("Shard") || material.endsWith("Fragment") -> "Cluster of ${material}s"
            else -> "$material Crystal"
        }
        "꾸러미" -> "Bundle of ${englishLootQuantity(material)}"
        "유물" -> "$material Relic"
        "조각" -> if (material.endsWith("Shard") || material.endsWith("Fragment")) {
            material
        } else {
            "$material Fragment"
        }
        "전리품" -> "$material Trophy"
        "밀봉함" -> "Sealed Cache of ${englishLootQuantity(material)}"
        "정수" -> "Essence of $material"
        "고서" -> "Tome of $material"
        "파편함" -> if (material.endsWith("Shard") || material.endsWith("Fragment")) {
            "Case of $material"
        } else {
            "Case of $material Fragments"
        }
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

    private fun englishLootQuantity(material: String): String =
        ENGLISH_LOOT_QUANTITIES.getValue(material)

    private fun japaneseLootName(material: String, form: String): String = when (form) {
        "표본" -> "${material}の標本"
        "부적" -> "${material}の護符"
        "결정" -> when {
            material.endsWith("結晶") -> material
            material.endsWith("欠片") || material.endsWith("片") -> "${material}の集合晶"
            else -> "${material}の結晶"
        }
        "꾸러미" -> "${material}の包み"
        "유물" -> "${material}の遺物"
        "조각" -> if (material.endsWith("欠片") || material.endsWith("片")) material else "${material}の欠片"
        "전리품" -> "${material}の戦利品"
        "밀봉함" -> "${material}の封印箱"
        "정수" -> "${material}の精髄"
        "고서" -> "${material}の古書"
        "파편함" -> if (material.endsWith("欠片") || material.endsWith("片")) {
            "${material}入りの箱"
        } else {
            "${material}の破片箱"
        }
        "문장" -> if (material.endsWith("印章")) material else "${material}の紋章"
        "핵" -> if (material.endsWith("核")) material else "${material}の核"
        "가루병" -> if (material in JAPANESE_POWDER_LIKE_MATERIALS) {
            "${material}入りの小瓶"
        } else {
            "${material}の粉末入り小瓶"
        }
        "장식품" -> "${material}の装飾品"
        "기념물" -> "${material}の記念品"
        else -> material + GameLocalization.translate(form, AppLanguage.JAPANESE)
    }

    private val TRANSCENDENT_PROGRESSION = Regex("초월 ([1-9][0-9]*)단식")
    private val LABYRINTH_GATE_MONSTER = Regex("^제([1-9][0-9]*)구역 관문지기 · (.+)$")

    private data class QuestTrophyNameParts(
        val baseName: String,
        val form: String,
    )

    private val QUEST_TROPHY_FORMS = listOf(
        "잔해",
        "표본",
        "흔적",
        "외피 조각",
        "조직 표본",
        "둥지 부스러기",
        "발자국 석고",
        "먹이 흔적",
        "재가루",
        "생태 기록",
        "활동 기록",
        "탈피 조각",
        "선혈 표본",
        "음영 스케치",
        "이동로 표식",
        "관찰 일지",
    )

    private val QUEST_MONSTER_BASE_NAMES: Set<String> by lazy {
        QuestMonsterCatalog.groups
            .flatMap { it.normals + it.elites + it.bosses }
            .mapTo(mutableSetOf()) { it.baseName }
    }

    private val ENGLISH_CLASS_NAMES = setOf(
        "Fighter",
        "Thief",
        "Ranger",
        "Mage",
        "Cleric",
        "Paladin",
    )

    private val REDUNDANT_EQUIPMENT_TERMS = setOf(
        "수습생의" to "견습식",
        "여행자의" to "모험식",
        "단단한" to "강화 철제",
        "왕가의" to "왕실제",
        "철빛" to "철제",
        "철빛" to "강화 철제",
        "철빛" to "정련 철제",
        "철빛" to "강철",
        "철빛" to "정련 강철",
        "잘 벼린" to "정련 철제",
        "잘 벼린" to "정련 강철",
        "별빛" to "별철",
        "신성한" to "성역식",
        "태고의" to "태고식",
        "운명의" to "운명식",
    )

    private val REDUNDANT_EQUIPMENT_ARCHETYPE_PREFIXES = mapOf(
        "수호자의" to setOf("수호 "),
        "용맹한" to setOf("용맹 "),
        "왕가의" to setOf("왕실 "),
        "그림자" to setOf("그림자 "),
        "별빛" to setOf("별빛 "),
    )

    private val REDUNDANT_MONSTER_TERMS = setOf(
        "잿빛" to "잿빛 슬라임",
        "고대의" to "고대 골렘",
        "안개 속" to "독안개 만티코어",
        "숨을 죽인" to "침묵 포식새",
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
        "든든한" to "頼もしい",
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
        "비전 강화" to "Arcane",
        "명장제" to "Masterwork",
        "정예 기사식" to "Elite Knight",
        "대가식" to "Grandmaster",
        "전승식" to "Heirloom",
        "은빛 합금" to "Silver Alloy",
        "별철" to "Starsteel",
        "용골 강화" to "Dragonbone",
        "성유 각인" to "Consecrated",
        "마정석" to "Mana-Crystal",
        "영혼결정" to "Soul-Crystal",
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
        "태고식" to "太古の",
        "운명식" to "運命の",
        "초월식" to "超越",
    )

    private val JAPANESE_EQUIPMENT_BASE_OVERRIDES = mapOf(
        "철면" to "フェイスガード",
        "철제신" to "サバトン",
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
        "레인저 창" to "レンジャースピア",
        "레인저 두건" to "レンジャーフード",
        "레인저 사냥복" to "レンジャーギア",
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

    private val ENGLISH_EQUIPMENT_BASE_OVERRIDES = mapOf(
        "철면" to "Faceguard",
        "철제신" to "Sabatons",
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
        "거미 독낭" to "クモの毒嚢",
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

    private val ENGLISH_LOOT_QUANTITIES = mapOf(
        "Horned Wolf Fang" to "Horned Wolf Fangs",
        "Crystal Shard" to "Crystal Shards",
        "Black Feather" to "Black Feathers",
        "Stardust" to "Stardust",
        "Magma Core" to "Magma Cores",
        "Silver Scale" to "Silver Scales",
        "Ancient Gear" to "Ancient Gears",
        "Spectral Cloth" to "Spectral Cloth",
        "Spider Venom Sac" to "Spider Venom Sacs",
        "Griffin Talon" to "Griffin Talons",
        "Spirit Ember" to "Spirit Embers",
        "Azure Sap" to "Azure Sap",
        "Twilight Pearl" to "Twilight Pearls",
        "Mithril Fragment" to "Mithril Fragments",
        "Sealing Stone" to "Sealing Stones",
        "Wyvern Hide" to "Wyvern Hides",
        "Abyssal Eye" to "Abyssal Eyes",
        "Golem Heart" to "Golem Hearts",
        "Moonlit Mushroom" to "Moonlit Mushrooms",
        "Royal Sigil" to "Royal Sigils",
        "Troll Tusk" to "Troll Tusks",
        "Banshee Tear" to "Banshee Tears",
        "Thunder Crystal" to "Thunder Crystals",
        "Crimson Sand" to "Crimson Sand",
    )

    private val POWDER_LIKE_MATERIALS = setOf("Stardust", "Crimson Sand")
    private val JAPANESE_POWDER_LIKE_MATERIALS = setOf("星屑", "赤砂")

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
        "모래 리자드맨" to "砂のリザードマン",
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
        "낮게 웅크린" to "Crouching",
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
        "먼지를 뒤집어쓴" to "Dust-Covered",
        "길목을 지키는" to "Road-Guarding",
        "종소리에 이끌린" to "Bell-Drawn",
        "새벽을 피하는" to "Dawn-Shunning",
        "유리빛에 젖은" to "Glasslight-Soaked",
        "백야를 떠도는" to "White-Night-Wandering",
        "기억을 핥는" to "Memory-Licking",
        "발자국 없는" to "Trackless",
        "투명한 껍질의" to "Transparent-Shelled",
        "봄물을 노리는" to "Springwater-Stalking",
        "균열에서 나온" to "Rift-Born",
        "소리를 삼킨" to "Sound-Devouring",
        "빛을 등진" to "Light-Shunning",
        "서리에 굳은" to "Frost-Hardened",
        "길을 잊은" to "Pathless",
        "이름을 훔친" to "Name-Stealing",
        "나이테에 갇힌" to "Tree-Ring-Bound",
        "차갑게 빛나는" to "Coldly Gleaming",
        "계절을 잃은" to "Seasonless",
        "깊은 곳에서 올라온" to "Deep-Risen",
        "귀환종을 따라온" to "Return-Bell-Following",
        "푸른 신호를 노리는" to "Blue-Signal-Stalking",
        "검은 유리를 두른" to "Black-Glass-Clad",
        "새벽종을 피하는" to "Dawn-Bell-Shunning",
        "재가루를 흘리는" to "Ash-Dust-Shedding",
        "길을 흉내 낸" to "Road-Mimicking",
        "메아리에 깨어난" to "Echo-Awakened",
        "봉인 틈에서 나온" to "Seal-Crevice-Born",
        "기억의 냄새를 맡는" to "Memory-Scenting",
        "오래된 표식을 품은" to "Ancient-Mark-Bearing",
        "하강로를 지키는" to "Descent-Route-Guarding",
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
        "먼지를 뒤집어쓴" to "埃まみれの",
        "길목을 지키는" to "道を守る",
        "종소리에 이끌린" to "鐘の音に引かれた",
        "새벽을 피하는" to "暁を避ける",
        "유리빛에 젖은" to "ガラスの光に濡れた",
        "백야를 떠도는" to "白夜をさまよう",
        "기억을 핥는" to "記憶を舐める",
        "발자국 없는" to "足跡のない",
        "투명한 껍질의" to "透明な殻の",
        "봄물을 노리는" to "春水を狙う",
        "균열에서 나온" to "亀裂から現れた",
        "소리를 삼킨" to "音を呑んだ",
        "빛을 등진" to "光に背を向けた",
        "서리에 굳은" to "霜に凍えた",
        "길을 잊은" to "道を忘れた",
        "이름을 훔친" to "名を盗んだ",
        "나이테에 갇힌" to "年輪に囚われた",
        "차갑게 빛나는" to "冷たく輝く",
        "계절을 잃은" to "季節を失った",
        "깊은 곳에서 올라온" to "深部から這い上がった",
        "귀환종을 따라온" to "帰還の鐘を追ってきた",
        "푸른 신호를 노리는" to "青い信号を狙う",
        "검은 유리를 두른" to "黒いガラスをまとった",
        "새벽종을 피하는" to "暁の鐘を避ける",
        "재가루를 흘리는" to "灰粉をこぼす",
        "길을 흉내 낸" to "道をまねた",
        "메아리에 깨어난" to "残響で目覚めた",
        "봉인 틈에서 나온" to "封印の隙間から現れた",
        "기억의 냄새를 맡는" to "記憶の匂いを嗅ぐ",
        "오래된 표식을 품은" to "古い標を宿す",
        "하강로를 지키는" to "降下路を守る",
    )
}
