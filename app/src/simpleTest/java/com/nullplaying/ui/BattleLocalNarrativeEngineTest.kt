package com.nullplaying.ui

import com.nullplaying.engine.BattleQaMatchFactory
import com.nullplaying.engine.ProjectionBattleEngine
import com.nullplaying.localization.AppLanguage
import com.nullplaying.model.BattleBuildStats
import com.nullplaying.model.BattleCondition
import com.nullplaying.model.BattleEquipmentSlot
import com.nullplaying.model.BattleEquipmentSnapshot
import com.nullplaying.model.BattleGuidance
import com.nullplaying.model.BattleHeroClass
import com.nullplaying.model.BattleProjectionSnapshot
import com.nullplaying.model.BattleRules
import com.nullplaying.model.BattleSeasonStanding
import com.nullplaying.model.BattleSkillKind
import com.nullplaying.model.BattleSkillSnapshot
import com.nullplaying.model.UserInitiatedBattleRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BattleLocalNarrativeEngineTest {
    @Test
    fun `catalog contains two thousand trilingual class and situation templates`() {
        val stats = BattleLocalNarrativeEngine.catalogStats()

        assertEquals(3, stats.languages)
        assertEquals(24, stats.situationTypes)
        assertEquals(6, stats.heroClasses)
        assertEquals(36, stats.classMatchups)
        assertEquals(2_000, stats.semanticTemplates)
        assertEquals(6_000, stats.localizedTexts)
    }

    @Test
    fun `same deterministic battle renders complete Korean English and Japanese narratives`() {
        val result = previewResult()

        val korean = BattleLocalNarrativeEngine.generate(result, AppLanguage.KOREAN, requestId = REQUEST_ID)
        val english = BattleLocalNarrativeEngine.generate(result, AppLanguage.ENGLISH, requestId = REQUEST_ID)
        val japanese = BattleLocalNarrativeEngine.generate(result, AppLanguage.JAPANESE, requestId = REQUEST_ID)

        listOf(korean, english, japanese).forEach { narrative ->
            assertEquals("local_template", narrative.source)
            assertEquals(BattleLocalNarrativeEngine.TEMPLATE_VERSION, narrative.model)
            assertEquals(0L, narrative.latencyMs)
            assertEquals(0, narrative.usage.inputTokens)
            assertEquals(0, narrative.usage.outputTokens)
            assertTrue(narrative.scenes.size in 3..5)
            assertTrue(narrative.scenes.all { scene -> scene.templateIds.isNotEmpty() })
            assertTrue(narrative.scenes.flatMap { it.templateIds }.distinct().size >= 6)
            assertFalse(narrative.scenes.any { it.text.contains('{') || it.text.contains('}') })
            assertFalse(
                narrative.scenes.any {
                    it.text.contains('—') || it.text.contains(" -") || it.text.trimStart().startsWith('-')
                },
            )
            assertTrue(narrative.scenes.all { battleNarrationSentences(it.text).size == 1 })
            assertTrue(
                narrative.scenes
                    .flatMap { battleNarrationSentences(it.text) }
                    .all { sentence -> battleNarrativeClauseCount(sentence, languageFor(narrative.languageTag)) <= 4 },
            )
        }

        assertEquals("ko", korean.languageTag)
        assertEquals("en", english.languageTag)
        assertEquals("ja", japanese.languageTag)
        assertTrue(korean.scenes.joinToString { it.text }.contains(Regex("[가-힣]")))
        assertFalse(english.scenes.joinToString { it.text }.contains(Regex("[가-힣]")))
        assertTrue(japanese.scenes.joinToString { it.text }.contains(Regex("[ぁ-んァ-ン一-龯]")))
        assertTrue(japanese.scenes.all { it.text.endsWith('。') })

        val endings = listOf(korean.scenes.last().text, english.scenes.last().text, japanese.scenes.last().text)
        assertFalse(endings[0].contains(Regex("승리|패배|무승부")))
        assertFalse(endings[1].contains(Regex("\\b(won|defeated|draw)\\b", RegexOption.IGNORE_CASE)))
        assertFalse(endings[2].contains(Regex("勝利|敗北|引き分け")))
    }

    @Test
    fun `battle opening alternates between immediate narration and one silent exchange`() {
        val decisions = (0 until 2_000).map { sequence ->
            battleStartsWithSilentExchange(
                battleId = "battle-$sequence",
                roundCount = 10,
                phaseCount = 5,
            )
        }
        val silentCount = decisions.count { it }

        assertTrue(silentCount in 900..1_100)
        assertTrue(decisions.any { it })
        assertTrue(decisions.any { !it })

        val rounds = previewResult().battle.rounds
        if (rounds.size > 3) {
            assertEquals(0, expandedLocalBattleRoundIndexes(rounds, 3, skipOpeningRound = false).first())
            assertEquals(1, expandedLocalBattleRoundIndexes(rounds, 3, skipOpeningRound = true).first())
        }

        val fixture = previewResult()
        assertTrue(fixture.battle.rounds.size > battleNarrativeAnchorCount(fixture.battle.rounds.size))
        val silentBattleId = (0 until 100)
            .map { "silent-opening-$it" }
            .first { battleId ->
                battleStartsWithSilentExchange(
                    battleId = battleId,
                    roundCount = fixture.battle.rounds.size,
                    phaseCount = battleNarrativeAnchorCount(fixture.battle.rounds.size),
                )
            }
        val silentFixture = fixture.copy(battle = fixture.battle.copy(battleId = silentBattleId))
        val silentNarrative = BattleLocalNarrativeEngine.generate(
            result = silentFixture,
            language = AppLanguage.KOREAN,
            requestId = REQUEST_ID,
        )
        val silentBeats = battlePlaybackBeats(silentFixture.battle, silentNarrative)

        assertTrue(silentBeats.count { it.text.isNotBlank() } >= silentNarrative.scenes.size)
    }

    @Test
    fun `narration tint and energy movement share the same atomic attack in every language`() {
        val fixture = previewResult()

        AppLanguage.entries.forEach { language ->
            val narrative = BattleLocalNarrativeEngine.generate(
                result = fixture,
                language = language,
                requestId = REQUEST_ID,
            )
            val beats = battlePlaybackBeats(fixture.battle, narrative)

            assertTrue(beats.count { it.text.isNotBlank() } >= narrative.scenes.size)
            beats.zipWithNext().forEach { (before, after) ->
                assertEquals(before.userEnergyAfter, after.userEnergyBefore)
                assertEquals(before.opponentEnergyAfter, after.opponentEnergyBefore)
            }
            beats.forEach { beat ->
                when {
                    beat.userState == BattleParagraphState.ATTACK -> {
                        assertEquals(BattleParagraphState.HIT, beat.opponentState)
                        assertTrue(beat.opponentEnergyAfter < beat.opponentEnergyBefore)
                    }
                    beat.opponentState == BattleParagraphState.ATTACK -> {
                        assertEquals(BattleParagraphState.HIT, beat.userState)
                        assertTrue(beat.userEnergyAfter < beat.userEnergyBefore)
                    }
                    else -> {
                        assertEquals(BattleParagraphState.DRAW, beat.userState)
                        assertEquals(BattleParagraphState.DRAW, beat.opponentState)
                    }
                }
            }
        }
    }

    @Test
    fun `selection is reproducible and recent template ids force a different composition`() {
        val result = previewResult()
        val first = BattleLocalNarrativeEngine.generate(result, AppLanguage.KOREAN, requestId = REQUEST_ID)
        val replay = BattleLocalNarrativeEngine.generate(result, AppLanguage.KOREAN, requestId = REQUEST_ID)

        assertEquals(first, replay)

        val recentIds = first.scenes.flatMap { it.templateIds }
        val next = BattleLocalNarrativeEngine.generate(
            result,
            AppLanguage.KOREAN,
            recentTemplateIds = recentIds,
            requestId = REQUEST_ID,
        )
        assertNotEquals(first.scenes.map { it.text }, next.scenes.map { it.text })
        assertNotEquals(first.scenes.first().templateIds.first(), next.scenes.first().templateIds.first())
    }

    @Test
    fun `Japanese punctuation is split into individual playback sentences`() {
        assertEquals(
            listOf("最初の攻防が始まった。", "次の一手が流れを変えた！", "勝負が決まった。"),
            battleNarrationSentences("最初の攻防が始まった。次の一手が流れを変えた！勝負が決まった。"),
        )
    }

    @Test
    fun `zero damage attack text explicitly matches a blocked energy bar`() {
        val source = mapOf(
            AppLanguage.KOREAN to "아린이 검을 내리쳤다.",
            AppLanguage.ENGLISH to "Arin brought the blade down.",
            AppLanguage.JAPANESE to "アリンは剣を振り下ろした。",
        )
        val defenders = mapOf(
            AppLanguage.KOREAN to "세라",
            AppLanguage.ENGLISH to "Sera",
            AppLanguage.JAPANESE to "セラ",
        )

        val rendered = AppLanguage.entries.associateWith { language ->
            battleDeflectedActionText(
                actionText = source.getValue(language),
                defenderName = defenders.getValue(language),
                language = language,
                variation = 0,
            )
        }

        assertTrue(rendered.getValue(AppLanguage.KOREAN).contains("피해는 없었다"))
        assertTrue(rendered.getValue(AppLanguage.ENGLISH).contains("took no damage"))
        assertTrue(rendered.getValue(AppLanguage.JAPANESE).contains("ダメージはなかった"))
        assertTrue(rendered.values.all { text -> !text.contains(" -") && !text.trimStart().startsWith('-') })

        val action = com.nullplaying.model.BattleRoundAction(
            actor = com.nullplaying.model.BattleSide.USER,
            kind = com.nullplaying.model.BattleActionKind.BASIC_ATTACK,
            damage = 0,
        )
        assertTrue(
            battleZeroDamageActionText("아린", "세라", action, emptyList(), AppLanguage.KOREAN, 0)
                .contains("세라가"),
        )
        assertTrue(
            battleZeroDamageActionText("Arin", "Sera", action, emptyList(), AppLanguage.ENGLISH, 2)
                .contains("missed"),
        )
        assertTrue(
            battleZeroDamageActionText("アリン", "セラ", action, emptyList(), AppLanguage.JAPANESE, 3)
                .contains("届かなかった"),
        )
        assertTrue(rendered.getValue(AppLanguage.KOREAN).contains("세라가"))
        assertTrue(rendered.getValue(AppLanguage.ENGLISH).contains("Sera"))
        assertTrue(rendered.getValue(AppLanguage.JAPANESE).contains("セラが"))

        val blockedFinisher = action.copy(
            kind = com.nullplaying.model.BattleActionKind.SKILL,
            skillId = "iron-slash",
            damage = 45,
            resolution = com.nullplaying.model.BattleActionResolution.BLOCKED,
        )
        val skill = com.nullplaying.model.BattleSkillSnapshot(
            skillId = "iron-slash",
            displayName = "강철 베기",
        )
        assertTrue(
            battleZeroDamageActionText(
                name = "아린",
                opponentName = "세라",
                action = blockedFinisher,
                skills = listOf(skill),
                language = AppLanguage.KOREAN,
                variation = 0,
                defenderDefeated = true,
            ).contains("세라가 아린의 강철 베기를 막았지만 남은 힘이 다했다"),
        )
        assertTrue(
            battleZeroDamageActionText(
                name = "Arin",
                opponentName = "Sera",
                action = blockedFinisher,
                skills = listOf(skill),
                language = AppLanguage.ENGLISH,
                variation = 0,
                defenderDefeated = true,
            ).let { it.contains("Sera blocked Arin's") && it.contains("no strength left") },
        )
        assertTrue(
            battleZeroDamageActionText(
                name = "アリン",
                opponentName = "セラ",
                action = blockedFinisher,
                skills = listOf(skill),
                language = AppLanguage.JAPANESE,
                variation = 0,
                defenderDefeated = true,
            ).let { it.contains("セラはアリンの") && it.contains("力が尽きた") },
        )

        val failedFinisher = blockedFinisher.copy(
            damage = 0,
            selfDamage = 20,
            finisher = true,
            finisherSucceeded = false,
            resolution = com.nullplaying.model.BattleActionResolution.MISSED,
        )
        assertTrue(
            battleZeroDamageActionText(
                name = "아린",
                opponentName = "세라",
                action = failedFinisher,
                skills = listOf(skill),
                language = AppLanguage.KOREAN,
                variation = 0,
                actorDefeated = true,
            ).contains("강철 베기가 빗나가자, 아린은 반동을 견디지 못했다"),
        )
        assertTrue(
            battleZeroDamageActionText(
                name = "Arin",
                opponentName = "Sera",
                action = failedFinisher,
                skills = listOf(skill),
                language = AppLanguage.ENGLISH,
                variation = 0,
                actorDefeated = true,
            ).contains("could not withstand the recoil"),
        )
        assertTrue(
            battleZeroDamageActionText(
                name = "アリン",
                opponentName = "セラ",
                action = failedFinisher,
                skills = listOf(skill),
                language = AppLanguage.JAPANESE,
                variation = 0,
                actorDefeated = true,
            ).contains("反動に耐えられなかった"),
        )
        val nonLethalRecoil = battleZeroDamageActionText(
            name = "아린",
            opponentName = "세라",
            action = failedFinisher,
            skills = listOf(skill),
            language = AppLanguage.KOREAN,
            variation = 0,
            actorDefeated = false,
        )
        assertTrue(nonLethalRecoil.contains("힘이 크게 빠졌다"))
        assertFalse(nonLethalRecoil.contains("반동을 견디지 못했다"))
    }

    @Test
    fun `all class matchups produce distinct class aware openings`() {
        val fixture = previewResult()
        val openings = BattleHeroClass.entries.flatMap { userClass ->
            BattleHeroClass.entries.map { opponentClass ->
                val varied = fixture.copy(
                    userClass = userClass,
                    opponentClass = opponentClass,
                    battle = fixture.battle.copy(
                        user = fixture.battle.user.copy(heroClass = userClass),
                        opponent = fixture.battle.opponent.copy(heroClass = opponentClass),
                    ),
                )
                BattleLocalNarrativeEngine.generate(
                    result = varied,
                    language = AppLanguage.KOREAN,
                    requestId = REQUEST_ID,
                ).scenes.first().text
            }
        }

        assertEquals(36, openings.distinct().size)
        assertTrue(openings.any { "중갑의 압박과" in it })
        assertTrue(openings.any { "거리 통제와" in it })
        assertTrue(openings.any { "그림자 기동이 맞붙었다" in it })
        assertFalse(openings.any { "압박와" in it || "통제과" in it })
        assertFalse(openings.any { "기동가 맞붙자" in it })
    }

    @Test
    fun `bulk local generation is varied concise and limited to four clauses`() {
        val fixture = previewResult()
        val variedResults = (0 until 2_000).map { sequence ->
            val userClass = BattleHeroClass.entries[sequence % BattleHeroClass.entries.size]
            val opponentClass = BattleHeroClass.entries[
                (sequence / BattleHeroClass.entries.size) % BattleHeroClass.entries.size
            ]
            val varied = fixture.copy(
                userClass = userClass,
                opponentClass = opponentClass,
                battle = fixture.battle.copy(
                    battleId = "123e4567-e89b-42d3-a456-${sequence.toString().padStart(12, '0')}",
                    serverSeed = 9_919L + sequence,
                    user = fixture.battle.user.copy(heroClass = userClass),
                    opponent = fixture.battle.opponent.copy(heroClass = opponentClass),
                ),
            )
            varied
        }
        val compositions = variedResults.map { varied ->
            BattleLocalNarrativeEngine.generate(
                result = varied,
                language = AppLanguage.KOREAN,
                requestId = REQUEST_ID,
            ).scenes.flatMap { it.templateIds }.joinToString("|")
        }

        assertEquals(2_000, compositions.distinct().size)
        AppLanguage.entries.forEach { language ->
            val narratives = variedResults.map { varied ->
                BattleLocalNarrativeEngine.generate(
                    result = varied,
                    language = language,
                    requestId = REQUEST_ID,
                )
            }
            val sentences = narratives.flatMap { narrative ->
                narrative.scenes.flatMap { scene -> battleNarrationSentences(scene.text) }
            }
            val finalLines = narratives.map { it.scenes.last().text }
            val lengths = sentences.map(String::length).sorted()
            fun percentile(percent: Double): Int = lengths[
                ((lengths.lastIndex * percent).toInt()).coerceIn(0, lengths.lastIndex)
            ]
            assertTrue(
                "${language.languageTag} exceeded four clauses: ${sentences.maxBy { battleNarrativeClauseCount(it, language) }}",
                sentences.all { battleNarrativeClauseCount(it, language) <= 4 },
            )
            when (language) {
                AppLanguage.KOREAN -> {
                    assertTrue(
                        "Korean sentence too long (${lengths.last()}): ${sentences.maxBy(String::length)}",
                        lengths.last() <= 38,
                    )
                    assertFalse("Malformed Korean object particle remained", sentences.any { "자을" in it })
                    assertFalse("Outcome appeared before the result screen", finalLines.any { it.contains(Regex("승리|패배|무승부")) })
                }
                AppLanguage.ENGLISH -> {
                    assertTrue(
                        "English sentence too long: ${sentences.maxBy { it.split(Regex("\\s+")).size }}",
                        sentences.all { it.split(Regex("\\s+")).size <= 12 },
                    )
                    assertFalse(
                        "Outcome appeared before the result screen",
                        finalLines.any { it.contains(Regex("\\b(won|defeated|draw)\\b", RegexOption.IGNORE_CASE)) },
                    )
                }
                AppLanguage.JAPANESE -> {
                    assertTrue("Japanese sentence too long: ${lengths.last()}", lengths.last() <= 38)
                    assertFalse("Outcome appeared before the result screen", finalLines.any { it.contains(Regex("勝利|敗北|引き分け")) })
                }
            }
            println(
                "BATTLE_SENTENCE_LENGTH ${language.languageTag} " +
                    "count=${lengths.size} median=${percentile(0.5)} " +
                    "p90=${percentile(0.9)} p95=${percentile(0.95)} max=${lengths.last()}",
            )
        }
    }

    @Test
    fun `visible skill logs keep actor skill and result but omit long equipment prose`() {
        val skill = BattleSkillSnapshot(
            skillId = "meteor-shot",
            displayName = "유성 사격",
            kind = BattleSkillKind.PIERCE,
        )
        val equipment = listOf(
            BattleEquipmentSnapshot(
                itemId = "very-long-helmet",
                displayName = "여행자의 견습식 돌격 두건 +3",
                slot = BattleEquipmentSlot.HEAD,
            ),
        )
        val action = com.nullplaying.model.BattleRoundAction(
            actor = com.nullplaying.model.BattleSide.USER,
            kind = com.nullplaying.model.BattleActionKind.SKILL,
            skillId = skill.skillId,
            damage = 300,
        )

        AppLanguage.entries.forEach { language ->
            BattleHeroClass.entries.forEach { heroClass ->
                val lines = (0 until 6).map { variation ->
                    BattleLocalNarrativeEngine.actionTextForTest(
                        name = "QA테스트",
                        action = action,
                        skills = listOf(skill),
                        equipment = equipment,
                        language = language,
                        variation = variation,
                        heroClass = heroClass,
                    )
                }

                assertEquals(6, lines.distinct().size)
                assertTrue(lines.all { "QA테스트" in it })
                assertTrue(lines.none { "여행자의 견습식 돌격 두건 +3" in it })
                assertTrue(lines.all { battleNarrationSentences(it).size == 1 })
                assertTrue(lines.all { battleNarrativeClauseCount(it, language) <= 2 })
                when (language) {
                    AppLanguage.KOREAN -> {
                        assertTrue(lines.all { it.length <= 38 })
                        assertTrue(lines.all { it.startsWith("QA테스트의 유성 사격이 ") })
                        assertFalse(lines.any { "유성 사격과 " in it || "유성 사격와 " in it })
                    }
                    AppLanguage.ENGLISH -> assertTrue(lines.all { it.split(Regex("\\s+")).size <= 10 })
                    AppLanguage.JAPANESE -> assertTrue(lines.all { it.length <= 38 })
                }
            }
        }
    }

    @Test
    fun `Korean recovery text uses a natural instrumental particle`() {
        val skill = BattleSkillSnapshot(
            skillId = "unyielding-breath",
            displayName = "불굴의 호흡",
            kind = BattleSkillKind.RECOVER,
        )
        val text = BattleLocalNarrativeEngine.actionTextForTest(
            name = "QA테스트",
            action = com.nullplaying.model.BattleRoundAction(
                actor = com.nullplaying.model.BattleSide.USER,
                kind = com.nullplaying.model.BattleActionKind.SKILL,
                skillId = skill.skillId,
                healing = 100,
                resolution = com.nullplaying.model.BattleActionResolution.RECOVERED,
            ),
            skills = listOf(skill),
            equipment = emptyList(),
            language = AppLanguage.KOREAN,
            variation = 0,
        )

        assertEquals("QA테스트는 불굴의 호흡으로 힘을 가다듬었다.", text)
    }

    @Test
    fun `all built in QA skills have English and Japanese names`() {
        val opponentPrefixes = listOf("luen", "mira", "kain", "sera", "eve", "arin", "noa", "raon")
        val opponentSuffixes = mapOf(
            "luen" to listOf("shadow-chain", "fog-step", "moon-feint", "silent-pierce"),
            "mira" to listOf("orbit-shot", "wind-pin", "hawk-volley", "sky-turn"),
            "kain" to listOf("ice-cleave", "iron-counter", "frost-charge", "glacier-roar"),
            "sera" to listOf("eclipse-wave", "gravity-knot", "star-fragment", "night-orbit"),
            "eve" to listOf("holy-ripple", "calm-prayer", "silver-verdict", "sanctuary-bell"),
            "arin" to listOf("dawn-counter", "oath-guard", "radiant-wall", "daybreak-charge"),
            "noa" to listOf("smoke-thrust", "false-step", "ash-cross", "haze-swap"),
            "raon" to listOf("blue-trail", "crosswind", "rain-volley", "tailwind-step"),
        )
        val coreSkills = listOf(
            "core-warrior-rush", "core-warrior-bash", "core-warrior-breath",
            "core-rogue-vital", "core-rogue-afterimage", "core-rogue-return",
            "core-ranger-volley", "core-ranger-trap", "core-ranger-breath",
            "core-mage-flame-ring", "core-mage-frost-ward", "core-mage-cycle",
            "core-cleric-judgment", "core-cleric-sanctuary", "core-cleric-heal",
            "core-paladin-radiance", "core-paladin-vow", "core-paladin-prayer",
        )
        val skillIds = opponentPrefixes.flatMap { prefix ->
            opponentSuffixes.getValue(prefix).map { suffix -> "$prefix-$suffix" }
        } + coreSkills

        assertEquals(50, skillIds.size)
        skillIds.forEach { skillId ->
            val english = localizedBattleSkillNameForTest(skillId, AppLanguage.ENGLISH)
            val japanese = localizedBattleSkillNameForTest(skillId, AppLanguage.JAPANESE)
            assertFalse("Korean leaked into English for $skillId: $english", english.contains(Regex("[가-힣]")))
            assertTrue("Japanese name missing for $skillId: $japanese", japanese.contains(Regex("[ぁ-んァ-ン一-龯]")))
            assertFalse("Korean leaked into Japanese for $skillId: $japanese", japanese.contains(Regex("[가-힣]")))
        }
    }

    private fun languageFor(tag: String): AppLanguage = AppLanguage.entries.first { it.languageTag == tag }

    private fun previewResult(): BattlePreviewResult {
        val user = combatant(
            id = "aster",
            name = "Aster",
            heroClass = BattleHeroClass.RANGER,
            skillPrefix = "aster",
            skillNames = listOf("Meteor Shot", "Wind Snare", "Hawk Rhythm"),
            itemPrefix = "Starweave",
            traits = listOf("TRAIT_012", "TRAIT_050", "TRAIT_071"),
        )
        val opponent = combatant(
            id = "ren",
            name = "Ren",
            heroClass = BattleHeroClass.ROGUE,
            skillPrefix = "ren",
            skillNames = listOf("Shadow Chain", "Mist Step", "Moon Feint"),
            itemPrefix = "Moonfall",
            traits = listOf("TRAIT_033", "TRAIT_061", "TRAIT_091"),
        )
        val request = UserInitiatedBattleRequest(
            battleId = BATTLE_ID,
            serverSeed = 9_919L,
            user = user,
            opponent = opponent,
            opponentReferenceScore = 1_018,
            rules = BattleRules(maxRounds = 8),
        )
        val battle = ProjectionBattleEngine.simulate(request)
        val match = BattleQaMatchFactory.Match(
            templateId = "test-ren",
            request = request,
            userCombatPower = 4_800,
            opponentCombatPower = 4_920,
            userEffectiveCombatPower = 4_800,
            opponentEffectiveCombatPower = 4_920,
        )
        return BattlePreviewResult(
            outcome = battle.outcome,
            userName = user.displayName,
            userClass = user.heroClass,
            userLevel = user.level,
            userPower = user.verifiedPower,
            opponentName = opponent.displayName,
            opponentClass = opponent.heroClass,
            opponentLevel = opponent.level,
            opponentPower = opponent.verifiedPower,
            opponentScore = 1_018,
            pointDelta = 10,
            scoreBefore = 1_000,
            standingAfter = BattleSeasonStanding(score = 1_010, completedBattles = 1),
            entriesAfter = 2,
            playerStance = BattleStance.BALANCED,
            opponentStance = BattleStance.GUARD,
            decisiveMoment = "deterministic fixture",
            match = match,
            battle = battle,
        )
    }

    private fun combatant(
        id: String,
        name: String,
        heroClass: BattleHeroClass,
        skillPrefix: String,
        skillNames: List<String>,
        itemPrefix: String,
        traits: List<String>,
    ): BattleProjectionSnapshot = BattleProjectionSnapshot(
        projectionId = id,
        displayName = name,
        heroClass = heroClass,
        level = 30,
        verifiedPower = 4_800,
        condition = BattleCondition.NORMAL,
        build = BattleBuildStats(40, 45, 62, 38, 35, 30),
        guidance = BattleGuidance.BALANCED,
        skills = skillNames.mapIndexed { index, displayName ->
            BattleSkillSnapshot(
                skillId = "$skillPrefix-skill-$index",
                displayName = displayName,
                kind = BattleSkillKind.entries[index],
                powerBasisPoints = 11_800 + index * 300,
                cooldownRounds = 1,
            )
        },
        equipment = BattleEquipmentSlot.entries.mapIndexed { index, slot ->
            BattleEquipmentSnapshot(
                itemId = "$skillPrefix-item-$index",
                displayName = "$itemPrefix ${slot.name.lowercase()}",
                slot = slot,
                rarity = "RARE",
            )
        },
        activeTraitIds = traits,
    )

    companion object {
        private const val BATTLE_ID = "123e4567-e89b-42d3-a456-426614174777"
        private const val REQUEST_ID = "123e4567-e89b-42d3-a456-426614174888"
    }
}
