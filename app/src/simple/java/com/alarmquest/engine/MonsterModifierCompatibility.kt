package com.alarmquest.engine

/** Keeps generated monster modifiers compatible with the creature's visible anatomy and behavior. */
internal object MonsterModifierCompatibility {
    fun compatibleModifiers(baseName: String, candidates: List<String>): List<String> =
        candidates.filter { modifier -> isCompatible(baseName, modifier) }

    /** Replaces an incompatible modifier without consuming gameplay RNG. */
    fun repairedName(
        sourceName: String,
        baseName: String,
        candidates: List<String> = emptyList(),
    ): String {
        if (baseName.isBlank() || !sourceName.endsWith(baseName)) return sourceName
        val modifier = sourceName.removeSuffix(baseName).trim()
        if (modifier.isBlank() || isCompatible(baseName, modifier)) return sourceName
        val compatible = compatibleModifiers(baseName, candidates)
        if (compatible.isEmpty()) return baseName
        val sourceIndex = candidates.indexOf(modifier).coerceAtLeast(0)
        return "${compatible[sourceIndex % compatible.size]} $baseName"
    }

    fun isCompatible(baseName: String, modifier: String): Boolean = when (modifier) {
        "거친 털의" -> baseName.hasSpecies(FURRED_SPECIES)
        "검은갈기" -> baseName.hasSpecies(MANED_SPECIES)
        "강철발톱" -> baseName.hasSpecies(CLAWED_SPECIES)
        "수정 껍질의" -> baseName.hasSpecies(SHELLED_SPECIES)
        "한쪽 눈의", "눈 밝은" -> baseName.hasSpecies(STANDARD_EYED_SPECIES)
        "낮게 웅크린" -> baseName.hasSpecies(CROUCHING_SPECIES)
        "노련한" -> baseName.hasSpecies(INTELLIGENT_SPECIES)
        "무리를 이탈한" -> baseName.hasSpecies(SOCIAL_SPECIES)
        "밤을 노리는" -> baseName.hasSpecies(STALKING_SPECIES)
        "날카로운" -> baseName.hasSpecies(NATURALLY_SHARP_SPECIES)
        "굶주린", "배고픈", "상처 입은" -> baseName.hasSpecies(LIVING_SPECIES)
        else -> !modifier.contains("포효") || baseName.hasSpecies(ROARING_SPECIES)
    }

    private fun String.hasSpecies(species: Set<String>): Boolean = species.any(::endsWith)

    private val FURRED_SPECIES = setOf(
        "늑대", "멧돼지", "박쥐", "사냥개", "들개", "하이에나", "여우", "들쥐", "왕쥐",
        "설치류", "뿔토끼", "토끼", "살쾡이", "산양", "사슴", "코볼트", "트롤",
        "오우거", "만티코어", "그리핀",
    )

    private val MANED_SPECIES = setOf(
        "늑대", "멧돼지", "하이에나", "살쾡이", "만티코어", "그리핀",
    )

    private val CLAWED_SPECIES = setOf(
        "늑대", "박쥐", "사냥개", "들개", "하이에나", "여우", "살쾡이", "트롤", "히드라",
        "와이번", "만티코어", "리자드맨", "코볼트", "그리핀", "오우거", "드레이크",
        "도마뱀",
    )

    private val SHELLED_SPECIES = setOf(
        "거미", "독거미", "딱정벌레", "벌레", "기생벌레", "거북", "게", "슬라임", "골렘",
        "도마뱀", "도롱뇽", "물뱀", "히드라", "드레이크",
    )

    private val STANDARD_EYED_SPECIES = setOf(
        "늑대", "멧돼지", "고블린", "트롤", "기사", "박쥐", "히드라", "와이번", "사냥개",
        "들개", "만티코어", "리자드맨", "코볼트", "그리핀", "오우거", "드레이크", "하이에나",
        "여우", "들쥐", "왕쥐", "설치류", "뿔토끼", "토끼", "살쾡이", "산양", "사슴",
        "도마뱀", "도롱뇽", "두꺼비", "물뱀", "거북", "물고기", "피라미", "까마귀",
        "부엉이", "새", "전령새", "포식새", "깃털새",
    )

    private val CROUCHING_SPECIES = setOf(
        "늑대", "멧돼지", "고블린", "트롤", "기사", "사냥개", "들개", "하이에나", "여우",
        "들쥐", "왕쥐", "설치류", "뿔토끼", "토끼", "살쾡이", "산양", "사슴", "도마뱀",
        "도롱뇽", "두꺼비", "물뱀", "히드라", "와이번", "만티코어", "리자드맨", "코볼트",
        "그리핀", "오우거", "드레이크",
    )

    private val INTELLIGENT_SPECIES = setOf(
        "고블린", "트롤", "기사", "리자드맨", "코볼트", "오우거",
    )

    private val SOCIAL_SPECIES = setOf(
        "늑대", "멧돼지", "고블린", "박쥐", "사냥개", "들개", "하이에나", "들쥐", "왕쥐",
        "설치류", "뿔토끼", "토끼", "산양", "사슴", "코볼트", "오우거", "그리핀", "까마귀",
        "부엉이", "새", "전령새", "포식새", "깃털새",
    )

    private val STALKING_SPECIES = setOf(
        "늑대", "거미", "독거미", "고블린", "트롤", "기사", "박쥐", "히드라", "와이번",
        "사냥개", "들개", "만티코어", "리자드맨", "코볼트", "그리핀", "오우거", "드레이크",
        "하이에나", "여우", "살쾡이", "도마뱀", "물뱀", "부엉이", "포식새",
    )

    private val NATURALLY_SHARP_SPECIES = setOf(
        "늑대", "사냥개", "들개", "하이에나", "살쾡이", "히드라", "와이번", "만티코어",
        "리자드맨", "그리핀", "드레이크", "까마귀", "부엉이", "포식새",
    )

    private val LIVING_SPECIES = setOf(
        "늑대", "거미", "독거미", "멧돼지", "고블린", "트롤", "박쥐", "히드라", "와이번",
        "사냥개", "들개", "만티코어", "리자드맨", "코볼트", "그리핀", "오우거", "드레이크",
        "하이에나", "여우", "들쥐", "왕쥐", "설치류", "뿔토끼", "토끼", "살쾡이", "산양",
        "사슴", "도마뱀", "도롱뇽", "두꺼비", "물뱀", "거북", "물고기", "피라미", "까마귀",
        "부엉이", "새", "전령새", "포식새", "깃털새", "나방", "딱정벌레", "벌레",
        "기생벌레", "좀", "게", "지렁이",
    )

    private val ROARING_SPECIES = setOf(
        "늑대", "멧돼지", "트롤", "히드라", "와이번", "사냥개", "들개", "만티코어", "그리핀",
        "오우거", "드레이크", "하이에나", "살쾡이",
    )
}
