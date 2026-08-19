package com.alarmquest.ui

import com.alarmquest.R
import com.alarmquest.engine.SkillCatalog
import com.alarmquest.engine.SkillDefinition
import com.alarmquest.model.HeroClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class SkillVfxGrammarTest {
    private val authored = SkillCatalog.all.filterNot(::shouldKeepLegacyPrimary)

    @Test
    fun `all non slash catalog ids own deterministic unique identities`() {
        assertEquals(560, authored.size)
        val first = authored.map(::skillVfxIdentity)
        val second = authored.map(::skillVfxIdentity)
        assertEquals(first, second)
        assertEquals(560, first.map { it.stableId }.distinct().size)
        assertTrue(first.all { it.schemaVersion == SKILL_VFX_IDENTITY_SCHEMA_VERSION })
    }

    @Test
    fun `domain separated roles do not reuse one variant tuple`() {
        authored.forEach { definition ->
            val roles = skillVfxIdentity(definition).roleVariants.values
            assertTrue(
                "${definition.catalogId} collapses every role to one variant",
                roles.distinct().size >= 5,
            )
        }
    }

    @Test
    fun `the exact eighteen motion grammars cover all non slash skills`() {
        assertEquals(SkillVfxGrammarId.entries.toSet(), authored.map { skillVfxGrammar(it).grammarId }.toSet())
        assertTrue(authored.all { skillVfxGrammar(it).materialKey == it.element.name })
    }

    @Test
    fun `every catalog owns a perceptually quantized grammar signature`() {
        val signatures = authored.map { skillVfxGrammar(it).perceptualSignature() }
        assertEquals(560, signatures.distinct().size)
        HeroClass.entries.forEach { heroClass ->
            val classSkills = authored.filter { it.heroClass == heroClass }
            val adjacent = classSkills.groupBy { it.unlockLevel }.toSortedMap().values.toList().zipWithNext()
            adjacent.forEach { (before, after) ->
                val beforeSet = before.map { skillVfxGrammar(it).perceptualSignature() }.toSet()
                val afterSet = after.map { skillVfxGrammar(it).perceptualSignature() }.toSet()
                assertTrue("$heroClass adjacent levels repeat a full grammar", beforeSet.intersect(afterSet).isEmpty())
            }
        }
    }

    @Test
    fun `growth stages are monotonic and hit bands only describe lane count`() {
        assertEquals(listOf(.10f, .20f, .32f), SkillVfxGrowthStage.entries.map { it.overflowFraction })
        HeroClass.entries.forEach { heroClass ->
            (0..4).forEach { candidate ->
                val stages = authored.filter { it.heroClass == heroClass && it.candidate == candidate }
                    .sortedBy { it.unlockLevel }
                    .map { skillVfxGrammar(it).growthStage.occupancyTarget }
                assertTrue(stages.zipWithNext().all { (before, after) -> before <= after })
            }
        }
        authored.forEach { definition ->
            val expected = when (definition.hitCount) {
                1 -> SkillVfxHitBand.SINGLE
                2 -> SkillVfxHitBand.DOUBLE
                in 3..5 -> SkillVfxHitBand.COMBO
                else -> SkillVfxHitBand.BARRAGE
            }
            assertEquals(expected, skillVfxGrammar(definition).hitBand)
        }
    }

    @Test
    fun `growth stage placement fills its overflow envelope without second fit`() {
        val canvasWidth = 361f
        val canvasHeight = 160f
        SkillVfxGrowthStage.entries.forEach { stage ->
            val placement = safeAuthoredAssetPlacement(
                canvasWidth = canvasWidth,
                canvasHeight = canvasHeight,
                centerX = canvasWidth / 2f,
                centerY = canvasHeight / 2f,
                width = canvasWidth * 2f,
                height = canvasHeight * 2f,
                rotationDegrees = 0f,
                overflowFraction = stage.overflowFraction,
            )
            val expectedLongAxis = 1f + stage.overflowFraction * 2f
            assertEquals(expectedLongAxis, placement.width / canvasWidth, .0001f)
            assertEquals(expectedLongAxis, placement.height / canvasHeight, .0001f)
            assertEquals(-stage.overflowFraction, (placement.centerX - placement.rotatedWidth / 2f) / canvasWidth, .0001f)
            assertEquals(1f + stage.overflowFraction, (placement.centerX + placement.rotatedWidth / 2f) / canvasWidth, .0001f)
        }
    }

    @Test
    fun `warrior non slash uses two to three times full bleed mass while slash remains untouched`() {
        val viewportWidth = 361f
        val viewportHeight = 160f
        val warrior = SkillCatalog.forClass(HeroClass.WARRIOR)
        val nonSlash = warrior.filterNot(::shouldKeepLegacyPrimary)
        assertEquals(60, nonSlash.size)

        fun maximumLongAxis(definition: SkillDefinition): Float =
            (0 until SKILL_VFX_END_MILLIS step 10).flatMap { elapsed ->
                authoredClassFramePlan(definition, elapsed, false, viewportWidth, viewportHeight)
            }.maxOf { frame ->
                val radians = Math.toRadians(frame.rotationDegrees.toDouble())
                val width = frame.widthFraction * viewportWidth
                val height = frame.heightFraction * viewportHeight
                val rotatedWidth = abs(cos(radians)).toFloat() * width + abs(sin(radians)).toFloat() * height
                val rotatedHeight = abs(sin(radians)).toFloat() * width + abs(cos(radians)).toFloat() * height
                maxOf(rotatedWidth / viewportWidth, rotatedHeight / viewportHeight)
            }

        nonSlash.forEach { definition ->
            val scale = requireNotNull(warriorNonSlashFullBleedScale(definition))
            assertTrue("${definition.catalogId} primary scale is too small", scale.primary >= 1.15f)
            assertTrue("${definition.catalogId} contact scale is too small", scale.contact >= 1.40f)
            val longAxis = maximumLongAxis(definition)
            assertTrue("${definition.catalogId} lacks reviewed full-bleed mass: $longAxis", longAxis >= .90f)
        }

        listOf(2, 3, 4).forEach { candidateOneBased ->
            val sequence = listOf(1, 10, 20).map { tier ->
                SkillCatalog.find("warrior_t${tier.toString().padStart(2, '0')}_c0$candidateOneBased")!!
            }.map(::maximumLongAxis)
            assertTrue("candidate $candidateOneBased mid did not grow: $sequence", sequence[1] > sequence[0])
            assertTrue("candidate $candidateOneBased high did not grow: $sequence", sequence[2] > sequence[1])
        }

        warrior.filter(::shouldKeepLegacyPrimary).forEach { definition ->
            assertNull("${definition.catalogId} slash unexpectedly changed", warriorNonSlashFullBleedScale(definition))
        }
    }

    @Test
    fun `warrior non slash keeps exact impact while each column owns a shorter release rhythm`() {
        val viewportWidth = 361f
        val viewportHeight = 160f
        val definitions = SkillCatalog.forClass(HeroClass.WARRIOR).filterNot(::shouldKeepLegacyPrimary)
        assertEquals(60, definitions.size)

        definitions.forEach { definition ->
            val stage = skillVfxGrammar(definition).growthStage
            val impactAt = definition.hitTimingsMillis.last()
            val contact = authoredClassFramePlan(
                definition,
                impactAt,
                false,
                viewportWidth,
                viewportHeight,
            ).singleOrNull { it.role == AuthoredLayerRole.CONTACT }
            assertNotNull("${definition.catalogId} has no exact-hit contact", contact)
            val minimumContactAlpha = when (stage) {
                SkillVfxGrowthStage.LOW -> .89f
                SkillVfxGrowthStage.MID -> .94f
                SkillVfxGrowthStage.HIGH -> .99f
            }
            assertTrue(
                "${definition.catalogId} contact is translucent at impact: ${contact?.alpha}",
                checkNotNull(contact).alpha >= minimumContactAlpha,
            )

            val firstStrong = (0 until SKILL_VFX_END_MILLIS step 10).firstOrNull { elapsed ->
                authoredClassFramePlan(definition, elapsed, false, viewportWidth, viewportHeight)
                    .any { it.role == AuthoredLayerRole.PRIMARY && it.alpha >= .45f }
            }
            val latestStrongOnset = when (checkNotNull(warriorNonSlashColumn(definition))) {
                WarriorNonSlashColumn.HEAVY -> when (stage) {
                    SkillVfxGrowthStage.LOW -> 310
                    SkillVfxGrowthStage.MID -> 280
                    SkillVfxGrowthStage.HIGH -> 250
                }
                WarriorNonSlashColumn.CHARGE -> when (stage) {
                    SkillVfxGrowthStage.LOW -> 300
                    SkillVfxGrowthStage.MID -> 270
                    SkillVfxGrowthStage.HIGH -> 240
                }
                WarriorNonSlashColumn.EARTH -> when (stage) {
                    SkillVfxGrowthStage.LOW -> 330
                    SkillVfxGrowthStage.MID -> 320
                    SkillVfxGrowthStage.HIGH -> 300
                }
            }
            assertTrue(
                "${definition.catalogId} primary starts late: $firstStrong",
                firstStrong != null && firstStrong <= latestStrongOnset,
            )

            val samples = (0 until SKILL_VFX_END_MILLIS step 10).associateWith { elapsed ->
                authoredClassFramePlan(definition, elapsed, false, viewportWidth, viewportHeight)
            }
            val primaryLast = samples.filterValues { frames ->
                frames.any { it.role == AuthoredLayerRole.PRIMARY && it.alpha >= .10f }
            }.keys.maxOrNull()
            val tailLast = samples.filterValues { frames -> frames.any { it.alpha >= .10f } }.keys.maxOrNull()
            val supportingLast = samples.filterValues { frames ->
                frames.any { it.role != AuthoredLayerRole.PRIMARY && it.alpha >= .10f }
            }.keys.maxOrNull()
            val primaryEnd = requireNotNull(
                warriorNonSlashRoleTimeline(definition, AuthoredLayerRole.PRIMARY),
            ).endMillis + impactAt
            val longestTailEnd = authoredCompositionRoles(definition, true)
                .mapNotNull { role -> warriorNonSlashRoleTimeline(definition, role)?.endMillis }
                .maxOrNull()!! + impactAt
            assertTrue("${definition.catalogId} body outlives its profile: $primaryLast/$primaryEnd", primaryLast != null && primaryLast <= primaryEnd)
            assertTrue("${definition.catalogId} tail outlives its profile: $tailLast/$longestTailEnd", tailLast != null && tailLast <= longestTailEnd)
            when (checkNotNull(warriorNonSlashColumn(definition))) {
                WarriorNonSlashColumn.CHARGE -> assertTrue(
                    "${definition.catalogId} thrust lost its trailing wake: $primaryLast/$supportingLast",
                    checkNotNull(supportingLast) - checkNotNull(primaryLast) >= 60,
                )
                WarriorNonSlashColumn.HEAVY,
                WarriorNonSlashColumn.EARTH,
                -> assertTrue(
                    "${definition.catalogId} body disappears before the supporting effect: $primaryLast/$supportingLast",
                    checkNotNull(primaryLast) - checkNotNull(supportingLast) >= 60,
                )
            }
            assertTrue(
                "${definition.catalogId} leaks into the clean tail",
                (1_240 until SKILL_VFX_END_MILLIS step 10).all { elapsed ->
                    authoredClassFramePlan(definition, elapsed, false, viewportWidth, viewportHeight).isEmpty()
                },
            )
        }
    }

    @Test
    fun `single slashes sweep diagonally and continuous slash keeps one thin streak family`() {
        val viewportWidth = 361f
        val viewportHeight = 160f
        val steel = checkNotNull(SkillCatalog.find("warrior_t01_c01"))
        val steelHit = steel.hitTimingsMillis.single()
        val approach = checkNotNull(
            legacyWarriorFramePlan(steel, steelHit - 100, false, viewportWidth, viewportHeight)
                .singleOrNull { it.role == AuthoredLayerRole.PRIMARY },
        )
        val contact = checkNotNull(
            legacyWarriorFramePlan(steel, steelHit, false, viewportWidth, viewportHeight)
                .singleOrNull { it.role == AuthoredLayerRole.PRIMARY },
        )
        val approachStrike = warriorResolvedStrikePoint(approach, viewportWidth, viewportHeight)
        val contactStrike = warriorResolvedStrikePoint(contact, viewportWidth, viewportHeight)
        assertTrue("steel slash lacks horizontal sweep", contactStrike.x - approachStrike.x >= .08f)
        assertTrue("steel slash lacks diagonal descent", contactStrike.y - approachStrike.y >= .07f)

        SkillCatalog.forClass(HeroClass.WARRIOR).filter { definition ->
            legacyWarriorDescriptor(definition)?.branch in setOf(
                LegacyWarriorBranch.STEEL_SLASH,
                LegacyWarriorBranch.SINGLE_SLASH,
            )
        }.forEach { definition ->
            val primary = checkNotNull(
                legacyWarriorFramePlan(
                    definition,
                    definition.hitTimingsMillis.single(),
                    false,
                    viewportWidth,
                    viewportHeight,
                ).singleOrNull { it.role == AuthoredLayerRole.PRIMARY },
            )
            assertEquals("${definition.catalogId} lost descending source mirror", -1f, primary.mirror, .0001f)
            assertTrue(
                "${definition.catalogId} tier rotation overrode the descending asset axis",
                abs(primary.rotationDegrees) <= 8f,
            )
        }

        val continuous = checkNotNull(SkillCatalog.find("warrior_t01_c05"))
        continuous.hitTimingsMillis.forEachIndexed { hitIndex, hitMillis ->
            val primary = checkNotNull(
                legacyWarriorFramePlan(continuous, hitMillis, false, viewportWidth, viewportHeight)
                    .singleOrNull { it.role == AuthoredLayerRole.PRIMARY && it.hitIndex == hitIndex },
            )
            assertEquals("continuous slash mixed a thick crescent at hit $hitIndex", R.drawable.vfx_warrior_01, primary.assetId)
        }
    }

    @Test
    fun `heavy descends on the impact column and earth body outlives its effects`() {
        val viewportWidth = 361f
        val viewportHeight = 160f
        SkillCatalog.forClass(HeroClass.WARRIOR).filter { it.candidate == 1 }.forEach { definition ->
            val hit = definition.hitTimingsMillis.last()
            val fallingBodies = authoredClassFramePlan(
                definition,
                hit - 140,
                false,
                viewportWidth,
                viewportHeight,
            ).filter { it.role in setOf(AuthoredLayerRole.SECONDARY, AuthoredLayerRole.PRIMARY) }
            assertTrue("${definition.catalogId} has no falling body", fallingBodies.isNotEmpty())
            fallingBodies.forEach { frame ->
                val strike = warriorResolvedStrikePoint(frame, viewportWidth, viewportHeight)
                assertEquals("${definition.catalogId} falling body leaves the impact column", .50f, strike.x, .015f)
            }
            val primary = checkNotNull(warriorNonSlashRoleTimeline(definition, AuthoredLayerRole.PRIMARY))
            assertTrue("${definition.catalogId} heavy body ends too early", primary.endMillis >= 650)
        }

        SkillCatalog.forClass(HeroClass.WARRIOR).filter { it.candidate == 3 }.forEach { definition ->
            val primary = checkNotNull(warriorNonSlashRoleTimeline(definition, AuthoredLayerRole.PRIMARY))
            val latestEffect = listOf(
                AuthoredLayerRole.CONTACT,
                AuthoredLayerRole.DEBRIS,
                AuthoredLayerRole.RESIDUAL,
                AuthoredLayerRole.FINISHER_RING,
                AuthoredLayerRole.FINISHER_ECHO,
            ).mapNotNull { warriorNonSlashRoleTimeline(definition, it)?.endMillis }.max()
            assertTrue("${definition.catalogId} ground body no longer owns the finish", primary.endMillis - latestEffect >= 160)
            assertTrue("${definition.catalogId} ground body still ends too early", primary.endMillis >= 720)
        }
    }

    @Test
    fun `warrior heavy charge and earth columns own distinct role landmarks`() {
        val representatives = listOf("warrior_t20_c02", "warrior_t20_c03", "warrior_t20_c04")
            .map { checkNotNull(SkillCatalog.find(it)) }
        val roles = listOf(
            AuthoredLayerRole.SECONDARY,
            AuthoredLayerRole.PRIMARY,
            AuthoredLayerRole.CONTACT,
            AuthoredLayerRole.DEBRIS,
            AuthoredLayerRole.RESIDUAL,
            AuthoredLayerRole.FINISHER_RING,
        )
        val landmarks = representatives.map { definition ->
            roles.map { role ->
                val timeline = checkNotNull(warriorNonSlashRoleTimeline(definition, role))
                listOf(timeline.startMillis, timeline.peakMillis, timeline.holdEndMillis, timeline.endMillis)
            }
        }
        assertEquals(3, landmarks.distinct().size)
        representatives.forEach { definition ->
            val contact = checkNotNull(warriorNonSlashRoleTimeline(definition, AuthoredLayerRole.CONTACT))
            assertEquals(0, contact.peakMillis)
            assertEquals(1f, contact.peakAlpha, .0001f)
        }
        assertTrue(landmarks[0] != landmarks[1] && landmarks[1] != landmarks[2])
    }

    @Test
    fun `warrior non slash impact stays attached to the primary axis and draws on top`() {
        val viewportWidth = 361f
        val viewportHeight = 160f
        SkillCatalog.forClass(HeroClass.WARRIOR).filterNot(::shouldKeepLegacyPrimary).forEach { definition ->
            val impactAt = definition.hitTimingsMillis.last()
            val frames = authoredClassFramePlan(definition, impactAt, false, viewportWidth, viewportHeight)
            val primary = checkNotNull(frames.singleOrNull { it.role == AuthoredLayerRole.PRIMARY })
            val contact = checkNotNull(frames.singleOrNull { it.role == AuthoredLayerRole.CONTACT })
            val rotationDelta = abs(primary.rotationDegrees - contact.rotationDegrees)
            val primaryStrike = warriorResolvedStrikePoint(primary, viewportWidth, viewportHeight)
            val contactStrike = warriorResolvedStrikePoint(contact, viewportWidth, viewportHeight)
            val centerDelta = kotlin.math.hypot(
                primaryStrike.x - contactStrike.x,
                primaryStrike.y - contactStrike.y,
            )
            assertTrue("${definition.catalogId} contact strike detached: $centerDelta", centerDelta <= .025f)
            assertTrue("${definition.catalogId} contact axis detached: $rotationDelta", rotationDelta <= 15f)
            assertEquals("${definition.catalogId} contact is not topmost", AuthoredLayerRole.CONTACT, frames.last().role)
        }
    }

    @Test
    fun `authored growth stages use but never exceed their overflow envelopes`() {
        val viewportWidth = 361f
        val viewportHeight = 160f
        val maxLongAxis = SkillVfxGrowthStage.entries.associateWith { 0f }.toMutableMap()
        val maxOverflow = SkillVfxGrowthStage.entries.associateWith { 0f }.toMutableMap()
        authored.forEach { definition ->
            val stage = skillVfxGrammar(definition).growthStage
            val limit = warriorNonSlashFullBleedScale(definition)?.overflowFraction
                ?: stage.overflowFraction
            (0 until SKILL_VFX_END_MILLIS step 20).forEach { elapsed ->
                authoredClassFramePlan(definition, elapsed, false, viewportWidth, viewportHeight).forEach { frame ->
                    val angle = Math.toRadians(frame.rotationDegrees.toDouble())
                    val width = frame.widthFraction * viewportWidth
                    val height = frame.heightFraction * viewportHeight
                    val rotatedWidth = abs(cos(angle)).toFloat() * width + abs(sin(angle)).toFloat() * height
                    val rotatedHeight = abs(sin(angle)).toFloat() * width + abs(cos(angle)).toFloat() * height
                    val left = frame.xFraction - rotatedWidth / viewportWidth / 2f
                    val right = frame.xFraction + rotatedWidth / viewportWidth / 2f
                    val top = frame.yFraction - rotatedHeight / viewportHeight / 2f
                    val bottom = frame.yFraction + rotatedHeight / viewportHeight / 2f
                    assertTrue("${definition.catalogId} crosses $stage left bound at $elapsed", left >= -limit - .001f)
                    assertTrue("${definition.catalogId} crosses $stage right bound at $elapsed", right <= 1f + limit + .001f)
                    assertTrue("${definition.catalogId} crosses $stage top bound at $elapsed", top >= -limit - .001f)
                    assertTrue("${definition.catalogId} crosses $stage bottom bound at $elapsed", bottom <= 1f + limit + .001f)
                    maxLongAxis[stage] = maxOf(
                        maxLongAxis.getValue(stage),
                        rotatedWidth / viewportWidth,
                        rotatedHeight / viewportHeight,
                    )
                    maxOverflow[stage] = maxOf(
                        maxOverflow.getValue(stage),
                        -left,
                        right - 1f,
                        -top,
                        bottom - 1f,
                    )
                }
            }
        }
        SkillVfxGrowthStage.entries.forEach { stage ->
            assertTrue("$stage remains visually small: ${maxLongAxis.getValue(stage)}", maxLongAxis.getValue(stage) >= 1f)
            assertTrue("$stage never uses its authored overflow: ${maxOverflow.getValue(stage)}", maxOverflow.getValue(stage) >= stage.overflowFraction * .80f)
        }
        assertTrue(maxLongAxis.getValue(SkillVfxGrowthStage.HIGH) >= maxLongAxis.getValue(SkillVfxGrowthStage.MID))
        assertTrue(maxOverflow.getValue(SkillVfxGrowthStage.HIGH) > maxOverflow.getValue(SkillVfxGrowthStage.LOW))
    }

    @Test
    fun `role windows remain finite and preserve the clean tail`() {
        authored.forEach { definition ->
            val grammar = skillVfxGrammar(definition)
            grammar.roles.values.forEach { role ->
                val window = role.window(-240, 480, SKILL_VFX_LAST_VISIBLE_MILLIS)
                if (window != null) {
                    assertTrue(window.first < window.second)
                    assertTrue(window.second <= SKILL_VFX_LAST_VISIBLE_MILLIS)
                }
            }
        }
    }

    @Test
    fun `damage overlay contract preserves every full alpha authored plane`() {
        val sentinels = listOf("rogue_t19_c01", "cleric_t19_c05", "ranger_t07_c02")
        sentinels.map { checkNotNull(SkillCatalog.find(it)) }.forEach { definition ->
            (0 until SKILL_VFX_END_MILLIS step 10).forEach { elapsed ->
                val planned = authoredClassFramePlan(definition, elapsed, false, 361f, 160f)
                val frames = authoredRenderableFramePlan(definition, elapsed, false, 361f, 160f)
                assertEquals(planned, frames)
                assertTrue(frames.all { it.safeAlphaCap == null })
            }
        }
    }

    @Test
    fun `reduced motion compositor stays at two sequential planes`() {
        authored.forEach { definition ->
            (0 until SKILL_VFX_END_MILLIS step 10).forEach { elapsed ->
                val frames = authoredRenderableFramePlan(definition, elapsed, true, 361f, 160f)
                assertTrue("${definition.catalogId} has ${frames.size} reduced planes at $elapsed", frames.size <= 2)
                assertTrue(frames.all { it.safeAlphaCap == null })
            }
        }
    }

    @Test
    fun `phase budgets prevent mandatory role starvation and reach stage peaks`() {
        val viewportWidth = 361f
        val viewportHeight = 160f
        authored.forEach definitionLoop@ { definition ->
            val budget = phaseCompositionBudget(definition)
            val samples = (0 until SKILL_VFX_END_MILLIS step 10).map { elapsed ->
                authoredRenderableFramePlan(
                    definition,
                    elapsed,
                    false,
                    viewportWidth,
                    viewportHeight,
                )
            }
            val vocabulary = samples.flatten().map { it.role }.toSet()
            if (isCreationEarthquake(definition)) {
                assertEquals(
                    setOf(AuthoredLayerRole.PRIMARY, AuthoredLayerRole.CONTACT),
                    vocabulary,
                )
                assertEquals(2, samples.maxOf { it.size })
                assertTrue(samples.all { it.size <= 2 })
                return@definitionLoop
            }
            assertTrue(
                "${definition.catalogId} starves ${budget.mandatoryRoles - vocabulary}",
                vocabulary.containsAll(budget.mandatoryRoles),
            )
            assertTrue("${definition.catalogId} misses peak ${budget.minimumPeak}", samples.maxOf { it.size } >= budget.minimumPeak)
            assertTrue("${definition.catalogId} exceeds ${budget.concurrentLimit}", samples.all { it.size <= budget.concurrentLimit })
        }
    }

    @Test
    fun `damage overlay never removes or dims a frame`() {
        val definition = checkNotNull(SkillCatalog.find("mage_t20_c05"))
        val sample = (0 until SKILL_VFX_END_MILLIS step 10).firstNotNullOf { elapsed ->
            val planned = authoredClassFramePlan(definition, elapsed, false, 361f, 160f)
            planned.takeIf { frames -> frames.size >= 4 }?.let { elapsed to it }
        }
        val rendered = authoredRenderableFramePlan(definition, sample.first, false, 361f, 160f)
        assertEquals(sample.second, rendered)
    }

    @Test
    fun `post compositor never relocates authored transforms`() {
        authored.forEach { definition ->
            (0 until SKILL_VFX_END_MILLIS step 20).forEach { elapsed ->
                val planned = authoredClassFramePlan(definition, elapsed, false, 361f, 160f)
                val composed = authoredRenderableFramePlan(definition, elapsed, false, 361f, 160f)
                val plannedByKey = planned.associateBy { Triple(it.role, it.hitIndex, it.instance) }
                composed.forEach frameLoop@ { frame ->
                    if (isCreationEarthquake(definition) && frame.role == AuthoredLayerRole.PRIMARY) {
                        return@frameLoop
                    }
                    val source = checkNotNull(plannedByKey[Triple(frame.role, frame.hitIndex, frame.instance)])
                    assertEquals(source.xFraction, frame.xFraction, .000001f)
                    assertEquals(source.yFraction, frame.yFraction, .000001f)
                    assertEquals(source.widthFraction, frame.widthFraction, .000001f)
                    assertEquals(source.heightFraction, frame.heightFraction, .000001f)
                    assertEquals(source.rotationDegrees, frame.rotationDegrees, .000001f)
                }
            }
        }
    }

    @Test
    fun `depth and finisher resolver hooks preserve separate role contracts`() {
        val definition = checkNotNull(SkillCatalog.find("mage_t19_c03"))
        val sentinelDepth = ClassBandDepthAssetResolver { _, identity, fallback ->
            assertEquals(definition.catalogId, identity.catalogId)
            ClassBandDepthAssets(fallback.secondary + 1, fallback.residual + 2)
        }
        val normal = classLayeredAssetSpec(definition)
        val fallbackRoles = ClassBandRoleAssetResolver { _, _, assets -> assets }
        val replaced = classLayeredAssetSpec(
            definition,
            depthResolver = sentinelDepth,
            roleResolver = fallbackRoles,
        )
        assertEquals(normal.secondary + 1, replaced.secondary)
        assertEquals(normal.residual + 2, replaced.residual)
        assertEquals(replaced.residual, replaced.finisherRing)
        assertNotEquals(replaced.secondary, replaced.finisherEcho)
        assertNotEquals(replaced.impactRing, replaced.finisherRing)
    }

    @Test
    fun `primary pair and finisher role resolver is identity stable`() {
        val definition = checkNotNull(SkillCatalog.find("ranger_t19_c01"))
        val fallbackRoleResolver = ClassBandRoleAssetResolver { _, _, assets -> assets }
        val fallback = classLayeredAssetSpec(definition, roleResolver = fallbackRoleResolver)
        val sentinel = ClassBandRoleAssetResolver { key, identity, assets ->
            assertEquals(classCandidateBandKey(definition), key)
            assertEquals(definition.catalogId, identity.catalogId)
            ClassBandRoleAssets(
                primaryA = assets.primaryA + 11,
                primaryB = assets.primaryB + 22,
                finisherRing = assets.finisherRing + 33,
                finisherEcho = assets.finisherEcho + 44,
            )
        }
        val resolved = classLayeredAssetSpec(definition, roleResolver = sentinel)
        assertEquals(fallback.primaryA + 11, resolved.primaryA)
        assertEquals(fallback.primaryB + 22, resolved.primaryB)
        assertTrue(resolved.primary == resolved.primaryA || resolved.primary == resolved.primaryB)
        assertEquals(fallback.finisherRing + 33, resolved.finisherRing)
        // The complete six-role fallback deliberately breaks the former anticipation/echo alias.
        assertNotEquals(resolved.secondary, resolved.finisherEcho)
        assertNotEquals(resolved.impactRing, resolved.finisherRing)
    }

    @Test
    fun `complete six role contract keeps anticipation and echo independent`() {
        authored.forEach { definition ->
            val resolved = classLayeredAssetSpec(definition)
            assertNotEquals("${definition.catalogId} aliases anticipation and echo", resolved.secondary, resolved.finisherEcho)
            assertTrue(resolved.primaryA != 0 && resolved.primaryB != 0)
            assertTrue(resolved.finisherRing != 0 && resolved.finisherEcho != 0)
        }
    }

    @Test
    fun `primary pair alternates per hit and both variants cover every authored cell`() {
        authored.filter { it.hitCount > 1 }.forEach { definition ->
            val spec = classLayeredAssetSpec(definition)
            val actual = definition.hitTimingsMillis.indices.map { hitIndex ->
                authoredPrimaryAssetForHit(definition, spec.primaryA, spec.primaryB, hitIndex)
            }
            if (spec.primaryA != spec.primaryB) {
                assertTrue("${definition.catalogId} does not alternate A B", actual.zipWithNext().all { it.first != it.second })
            }
        }
        authored.groupBy(::classCandidateBandKey).forEach { (key, definitions) ->
            val used = definitions.map { definition ->
                val spec = classLayeredAssetSpec(definition)
                authoredPrimaryAssetForHit(definition, spec.primaryA, spec.primaryB, 0)
            }.toSet()
            val expected = definitions.flatMap { definition ->
                val spec = classLayeredAssetSpec(definition)
                listOf(spec.primaryA, spec.primaryB)
            }.toSet()
            assertEquals("$key does not expose its complete A B pair", expected, used)
        }
    }
}
