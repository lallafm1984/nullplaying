package com.alarmquest.ui

import com.alarmquest.R
import com.alarmquest.engine.SkillCatalog
import com.alarmquest.engine.SkillElement
import com.alarmquest.engine.SkillFinisher
import com.alarmquest.engine.SkillMotion
import com.alarmquest.engine.SkillTimingProfile
import com.alarmquest.model.HeroClass
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CombatMotionTest {
    private fun drawableEntryNames(): Map<Int, String> = R.drawable::class.java.fields
        .filter { it.type == Int::class.javaPrimitiveType }
        .associate { field -> field.getInt(null) to field.name }

    @Test
    fun `damage overlay owns an explicit top plane and density independent outline`() {
        assertEquals(100f, DAMAGE_TEXT_Z_INDEX, .0001f)
        assertEquals(3f, DAMAGE_TEXT_STROKE_DP, .0001f)
    }

    @Test
    fun `all 100 warrior skills route to detailed 16-frame sprite sheets only`() {
        val warriors = SkillCatalog.all.filter { it.heroClass == HeroClass.WARRIOR }
        assertEquals(100, warriors.size)
        assertEquals(100, warriors.mapNotNull { warriorDetailedSpriteSheetAssetId(it.catalogId) }.distinct().size)
        warriors.forEach { definition ->
            assertTrue(
                "missing Detailed sprite for ${definition.catalogId}",
                warriorDetailedSpriteSheetAssetId(definition.catalogId) != null,
            )
        }
        SkillCatalog.all.filter { it.heroClass != HeroClass.WARRIOR }.forEach { definition ->
            assertEquals(definition.catalogId, null, warriorDetailedSpriteSheetAssetId(definition.catalogId))
        }
    }

    @Test
    fun `warrior detailed sprite timing matches the browser 420 490 720 checkpoints`() {
        assertEquals(null, warriorDetailedSpriteFrameIndex(-1, reducedMotion = false))
        assertEquals(0, warriorDetailedSpriteFrameIndex(0, reducedMotion = false))
        assertEquals(6, warriorDetailedSpriteFrameIndex(420, reducedMotion = false))
        assertEquals(7, warriorDetailedSpriteFrameIndex(490, reducedMotion = false))
        assertEquals(11, warriorDetailedSpriteFrameIndex(720, reducedMotion = false))
        assertEquals(15, warriorDetailedSpriteFrameIndex(999, reducedMotion = false))
        assertEquals(null, warriorDetailedSpriteFrameIndex(1_000, reducedMotion = false))
        assertEquals(8, warriorDetailedSpriteFrameIndex(420, reducedMotion = true))
        assertEquals(null, warriorDetailedSpriteFrameIndex(780, reducedMotion = true))
    }

    @Test
    fun `export authoritative authored vfx frames for the web lab`() {
        val outputValue = System.getProperty("alarmquest.vfx.export")
            ?: System.getenv("ALARMQUEST_VFX_EXPORT")
            ?: return
        val outputDir = Path.of(outputValue).toAbsolutePath().normalize()
        Files.createDirectories(outputDir)
        val metadataFile = outputDir.resolve("skills.psv")
        val framesFile = outputDir.resolve("frames.psv")
        val presentationFile = outputDir.resolve("presentation.psv")
        val assetsFile = outputDir.resolve("assets.psv")
        val manifestFile = outputDir.resolve("manifest.psv")
        // The reference Android device and the browser phone shell both expose a
        // 361.dp battle plane after the 16.dp horizontal insets.
        val viewportWidth = 361f
        // CombatPanel renders SkillEffectLayer in the 160.dp battle plane below the
        // 58.dp monster header. The web lab uses the same 360 x 160 logical viewport;
        // exporting at 180 changes safe-placement clamping and makes authored effects
        // such as Ground Impact visibly drift between Android and the browser.
        val viewportHeight = 160f
        val sampleStepMillis = 10
        val drawableNames = drawableEntryNames()
        val assetRows = linkedMapOf<Int, String>()
        fun exportSampleTimes(definition: com.alarmquest.engine.SkillDefinition): List<Int> =
            ((0..SKILL_PRESENTATION_DURATION_MILLIS step sampleStepMillis) + definition.hitTimingsMillis)
                .distinct()
                .sorted()

        Files.newBufferedWriter(manifestFile).use { writer ->
            writer.appendLine("viewportWidth|viewportHeight|sampleStepMillis|presentationDurationMillis|vfxEndMillis|attackBoundaryMillis")
            writer.appendLine(
                "$viewportWidth|$viewportHeight|$sampleStepMillis|$SKILL_PRESENTATION_DURATION_MILLIS|" +
                    "$SKILL_VFX_END_MILLIS|1400",
            )
        }

        Files.newBufferedWriter(metadataFile).use { writer ->
            writer.appendLine(
                "catalogId|class|level|candidate|name|branchKey|action|flow|impactStyle|path|growthBand|" +
                    "tierVariant|primaryResolverKey|choreographyKey|identitySchema|identityId|grammarId|growthStage|" +
                    "hitBand|perceptualSignature|roleGrammar|element|hitWeights|hitTimings|presentationHitTimings|presentationGroups|" +
                    "primaryAsset|primaryAssets|secondaryAsset|impactAssets|debrisAssets|residualAsset|finisherRingAsset|finisherEchoAsset|legacyRecipe|" +
                    "normalRoleCount|normalPeakConcurrent|minimumPeakConcurrent|reducedMaxConcurrent",
            )
            SkillCatalog.all.forEach { definition ->
                val plan = semanticVfxPlan(definition)
                val signature = classVfxSignature(definition)
                val assetSpec = classLayeredAssetSpec(definition)
                val legacy = legacyWarriorDescriptor(definition)
                val presentationHits = skillPresentationHits(definition)
                val identity = skillVfxIdentity(definition)
                val grammar = skillVfxGrammar(definition)
                val reducedMax = (0..SKILL_PRESENTATION_DURATION_MILLIS step sampleStepMillis)
                    .maxOf { elapsed ->
                        if (legacy != null) {
                            legacyWarriorFramePlan(
                                definition,
                                elapsed,
                                reducedMotion = true,
                                viewportWidth,
                                viewportHeight,
                            ).size
                        } else {
                            authoredRenderableFramePlan(
                                definition,
                                elapsed,
                                reducedMotion = true,
                                viewportWidth,
                                viewportHeight,
                            ).size
                        }
                    }
                val normalFrames = if (legacy != null) {
                    (0..SKILL_PRESENTATION_DURATION_MILLIS step sampleStepMillis).map { elapsed ->
                        legacyWarriorFramePlan(definition, elapsed, false, viewportWidth, viewportHeight)
                    }
                } else {
                    (0..SKILL_PRESENTATION_DURATION_MILLIS step sampleStepMillis).map { elapsed ->
                        authoredRenderableFramePlan(definition, elapsed, false, viewportWidth, viewportHeight)
                    }
                }
                val actualNormalRoles = normalFrames.flatten().map { it.role }.toSet()
                val normalPeak = normalFrames.maxOfOrNull { it.size } ?: 0
                writer.appendLine(
                    listOf(
                        definition.catalogId,
                        definition.heroClass.name,
                        definition.unlockLevel,
                        definition.candidate,
                        definition.name.replace('|', '/'),
                        legacy?.branchKey ?: "AUTHORED_CLASS:${definition.heroClass.name}:${signature.path.name}",
                        plan.action.name,
                        plan.flow.name,
                        plan.impactStyle.name,
                        signature.path.name,
                        signature.growthBand,
                        signature.tierVariant,
                        classCandidateBandKey(definition).stableId,
                        classBandChoreography(definition).stableId,
                        identity.schemaVersion,
                        identity.stableId,
                        grammar.grammarId.name,
                        grammar.growthStage.name,
                        grammar.hitBand.name,
                        grammar.perceptualSignature().replace('|', '~'),
                        // PSV owns the pipe delimiter. Role descriptors use pipes internally, so
                        // encode those separators before writing one logical skill row.
                        grammar.exportValue().replace('|', '~'),
                        definition.element.name,
                        definition.hitWeights.joinToString(","),
                        definition.hitTimingsMillis.joinToString(","),
                        presentationHits.joinToString(",") { it.timingMillis.toString() },
                        presentationHits.joinToString(";") { hit ->
                            "${hit.timingMillis}:${hit.sourceHitIndices.joinToString(",")}:${hit.weight}"
                        },
                        legacy?.primaryAssetId ?: if (isCreationEarthquake(definition)) {
                            creationEarthquakeSpriteAssetIds.first()
                        } else {
                            assetSpec.primary
                        },
                        when {
                            legacy != null -> legacy.primaryAssetId
                            isCreationEarthquake(definition) -> creationEarthquakeSpriteAssetIds.joinToString(",")
                            else -> listOf(assetSpec.primaryA, assetSpec.primaryB).joinToString(",")
                        },
                        if (legacy != null || isCreationEarthquake(definition)) "" else assetSpec.secondary,
                        if (legacy != null) "" else listOf(assetSpec.impactPoint, assetSpec.impactFracture, assetSpec.impactRing).joinToString(","),
                        if (legacy != null || isCreationEarthquake(definition)) "" else {
                            listOf(assetSpec.debrisPoint, assetSpec.debrisFracture, assetSpec.debrisRing).joinToString(",")
                        },
                        if (legacy == null && AuthoredLayerRole.RESIDUAL in actualNormalRoles) assetSpec.residual else "",
                        if (legacy == null && AuthoredLayerRole.FINISHER_RING in actualNormalRoles) assetSpec.finisherRing else "",
                        if (legacy == null && AuthoredLayerRole.FINISHER_ECHO in actualNormalRoles) assetSpec.finisherEcho else "",
                        legacy?.let {
                            definition.hitTimingsMillis.indices.joinToString(",") { hitIndex ->
                                val choreography = semanticHitChoreography(definition, hitIndex)
                                val recipe = choreography.recipe
                                "h$hitIndex=${recipe.archetype.name}:${recipe.index}" +
                                    "@r${choreography.rotationDegrees}" +
                                    "@m${choreography.mirrorDirection}" +
                                    "@s${choreography.widthScale}"
                            }
                        } ?: "",
                        actualNormalRoles.size,
                        normalPeak,
                        if (legacy != null) {
                            1
                        } else if (isCreationEarthquake(definition)) {
                            2
                        } else {
                            phaseCompositionBudget(definition).minimumPeak
                        },
                        reducedMax,
                    ).joinToString("|"),
                )
            }
        }

        Files.newBufferedWriter(framesFile).use { writer ->
            writer.appendLine("catalogId|elapsed|reduced|$AUTHORED_FRAME_PSV_HEADER")
            SkillCatalog.all.forEach { definition ->
                val legacy = legacyWarriorDescriptor(definition)
                listOf(false, true).forEach { reducedMotion ->
                    exportSampleTimes(definition).forEach { elapsed ->
                        val frames = if (legacy != null) {
                            legacyWarriorFramePlan(
                                definition,
                                elapsed,
                                reducedMotion,
                                viewportWidth,
                                viewportHeight,
                            )
                        } else {
                            authoredRenderableFramePlan(
                                definition,
                                elapsed,
                                reducedMotion,
                                viewportWidth,
                                viewportHeight,
                            )
                        }
                        frames.forEach { frame ->
                            writer.appendLine(
                                "${definition.catalogId}|$elapsed|${if (reducedMotion) 1 else 0}|${frame.toPsv()}",
                            )
                            assetRows.putIfAbsent(frame.assetId, frame.role.name)
                        }
                    }
                }
            }
        }

        Files.newBufferedWriter(presentationFile).use { writer ->
            writer.appendLine(
                "catalogId|elapsed|reduced|damageVisible|damage|damageAlpha|damageScale|damageY|damageFinal|" +
                    "energy|labelAlpha|cameraX|cameraY|cameraScale",
            )
            SkillCatalog.all.forEach { definition ->
                listOf(false, true).forEach { reducedMotion ->
                    exportSampleTimes(definition).forEach { elapsed ->
                        val damage = skillDamageFrame(elapsed, definition, totalDamage = 10_000L)
                        val camera = if (reducedMotion) SkillCameraFrame() else skillCameraFrame(elapsed, definition)
                        writer.appendLine(
                            listOf(
                                definition.catalogId,
                                elapsed,
                                if (reducedMotion) 1 else 0,
                                if (damage.visible) 1 else 0,
                                damage.damage,
                                if (reducedMotion && damage.visible) 1f else damage.alpha,
                                if (reducedMotion) 1f else damage.scale,
                                if (reducedMotion) 0f else damage.translationY,
                                if (damage.isFinal) 1 else 0,
                                skillEnergyFraction(elapsed, .78f, .16f, definition, reducedMotion),
                                skillLabelAlpha(elapsed, definition),
                                camera.translationX,
                                camera.translationY,
                                camera.scale,
                            ).joinToString("|"),
                        )
                    }
                }
            }
        }

        Files.newBufferedWriter(assetsFile).use { writer ->
            writer.appendLine("assetId|assetName|firstRole")
            assetRows.forEach { (assetId, role) ->
                val assetName = checkNotNull(drawableNames[assetId]) {
                    "No R.drawable entry name for VFX asset id $assetId"
                }
                writer.appendLine("$assetId|$assetName|$role")
            }
        }

        assertEquals(600, Files.readAllLines(metadataFile).size - 1)
        assertTrue(Files.size(framesFile) > 0L)
        val exportedSampleCount = SkillCatalog.all.sumOf { exportSampleTimes(it).size }
        assertEquals(exportedSampleCount * 2 + 1, Files.readAllLines(presentationFile).size)
        assertTrue(Files.readAllLines(assetsFile).size > 1)
        assertTrue(
            "every rendered VFX asset must have an exported drawable name",
            assetRows.keys.all(drawableNames::containsKey),
        )
    }

    @Test
    fun `multi hit skills alternate contact silhouettes before the semantic finisher`() {
        SkillCatalog.all.filter { it.hitCount >= 3 }.forEach { definition ->
            val layers = definition.hitTimingsMillis.indices.map { classImpactLayerFor(definition, it) }
            assertTrue("${definition.catalogId} repeats one contact", layers.distinct().size >= 2)
        }
    }

    @Test
    fun `all class authored primaries align their native axis to semantic travel`() {
        SkillCatalog.all.filterNot(::shouldKeepLegacyPrimary).forEach { definition ->
            val axis = classPrimaryAxis(definition.heroClass, definition.candidate)
            definition.hitTimingsMillis.indices.forEach { hitIndex ->
                val motion = classPrimaryMotion(definition, hitIndex)
                val rotation = classPrimaryRotationDegrees(definition, hitIndex)
                if (axis.nativeDegrees != null && motion.targetDegrees != null) {
                    val aligned = (axis.nativeDegrees + rotation).mod(360f)
                    assertEquals(
                        "${definition.catalogId} ${axis.label} is not aligned at hit $hitIndex",
                        motion.targetDegrees.mod(360f),
                        aligned,
                        0.0001f,
                    )
                } else {
                    assertEquals("${definition.catalogId} radial asset rotated", 0f, rotation, 0.0001f)
                }
            }
        }
    }

    @Test
    fun `semantic motion axes are fixed for descend charge ascend and convergence`() {
        SkillCatalog.all.forEach { definition ->
            definition.hitTimingsMillis.indices.forEach { hitIndex ->
                val plan = semanticVfxPlan(definition)
                val motion = classPrimaryMotion(definition, hitIndex)
                when (plan.flow) {
                    SemanticVfxFlow.TOP_TO_BOTTOM -> {
                        assertEquals(0f, motion.startXFraction, 0.0001f)
                        assertTrue("${definition.catalogId} does not descend", motion.startYFraction < 0f)
                        assertEquals(90f, motion.targetDegrees)
                    }
                    SemanticVfxFlow.BOTTOM_TO_TOP -> {
                        assertEquals(0f, motion.startXFraction, 0.0001f)
                        assertTrue("${definition.catalogId} does not ascend", motion.startYFraction > 0f)
                        assertEquals(-90f, motion.targetDegrees)
                    }
                    SemanticVfxFlow.LEFT_TO_RIGHT -> {
                        assertEquals(0f, motion.startYFraction, 0.0001f)
                        assertTrue("${definition.catalogId} has no horizontal travel", motion.startXFraction != 0f)
                    }
                    SemanticVfxFlow.CENTER_OUT,
                    SemanticVfxFlow.CLOCKWISE,
                    -> {
                        assertEquals(0f, motion.startXFraction, 0.0001f)
                        assertEquals(0f, motion.startYFraction, 0.0001f)
                        assertEquals(null, motion.targetDegrees)
                    }
                    SemanticVfxFlow.OUTSIDE_IN -> {
                        assertTrue(
                            "${definition.catalogId} does not converge",
                            motion.startXFraction != 0f || motion.startYFraction != 0f,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `authored depth paths never overturn the semantic attack axis`() {
        SkillCatalog.all.filterNot(::shouldKeepLegacyPrimary).forEach { definition ->
            definition.hitTimingsMillis.indices.forEach { hitIndex ->
                val motion = classPrimaryMotion(definition, hitIndex)
                val start = classAuthoredPathStart(motion, classVfxSignature(definition))
                when (motion.targetDegrees) {
                    0f, 180f -> assertTrue(
                        "${definition.catalogId} horizontal travel became vertical",
                        kotlin.math.abs(start.x) > kotlin.math.abs(start.y),
                    )
                    90f, -90f -> assertTrue(
                        "${definition.catalogId} vertical travel became horizontal",
                        kotlin.math.abs(start.y) > kotlin.math.abs(start.x),
                    )
                }
            }
        }
    }

    @Test
    fun `warrior authored axes match descent charge and earth families`() {
        val down = SkillCatalog.all.single { it.catalogId == "warrior_t01_c02" }
        val charge = SkillCatalog.all.single { it.catalogId == "warrior_t01_c03" }
        val earth = SkillCatalog.all.single { it.catalogId == "warrior_t01_c04" }

        assertEquals(ClassPrimaryAxis(90f, "vertical descent"), classPrimaryAxis(down.heroClass, down.candidate))
        assertEquals(R.drawable.vfx8_warrior_slash_primary, classPrimaryResource(down))
        assertEquals(0f, classPrimaryRotationDegrees(down, 0), 0.0001f)
        assertTrue(classPrimaryMotion(down, 0).startYFraction < 0f)

        assertEquals(ClassPrimaryAxis(0f, "horizontal charge"), classPrimaryAxis(charge.heroClass, charge.candidate))
        assertEquals(R.drawable.vfx8_warrior_slash_primary, classPrimaryResource(charge))
        assertEquals(0f, classPrimaryRotationDegrees(charge, 0), 0.0001f)
        assertTrue(classPrimaryMotion(charge, 0).startXFraction < 0f)

        assertEquals(R.drawable.vfx8_warrior_slash_primary, classPrimaryResource(earth))
        assertEquals(null, classPrimaryAxis(earth.heroClass, earth.candidate).nativeDegrees)
        assertEquals(0f, classPrimaryRotationDegrees(earth, 0), 0.0001f)
    }

    @Test
    fun `every catalog skill resolves the intended zero based class resource`() {
        SkillCatalog.all.forEach { definition ->
            assertEquals(
                "${definition.catalogId} resolves the wrong class primary",
                classPrimaryResources(definition.heroClass)[definition.candidate],
                classPrimaryResource(definition),
            )
        }
    }

    @Test
    fun `every non slash skill uses distinct layered resources`() {
        SkillCatalog.all.filterNot(::shouldKeepLegacyPrimary).forEach { definition ->
            val spec = classLayeredAssetSpec(definition)
            val resources = listOf(
                spec.primary,
                spec.secondary,
                spec.impactPoint,
                spec.impactFracture,
                spec.impactRing,
                spec.debrisPoint,
                spec.debrisFracture,
                spec.debrisRing,
                spec.residual,
                spec.finisherRing,
                spec.finisherEcho,
            )
            assertTrue("${definition.catalogId} has an empty VFX role", resources.all { it != 0 })
            assertTrue(
                "${definition.catalogId} is still a single-image effect",
                resources.distinct().size >= if (warriorNonSlashColumn(definition) == null) 4 else 5,
            )
            assertTrue("${definition.catalogId} reuses primary as secondary", spec.primary != spec.secondary)
            assertTrue("${definition.catalogId} reuses primary as residual", spec.primary != spec.residual)
            assertTrue("${definition.catalogId} aliases secondary and echo", spec.secondary != spec.finisherEcho)
        }
    }

    @Test
    fun `refreshed class candidates own distinct secondary and residual silhouettes`() {
        listOf(
            HeroClass.ROGUE,
            HeroClass.RANGER,
            HeroClass.MAGE,
            HeroClass.CLERIC,
            HeroClass.PALADIN,
        ).forEach { heroClass ->
            val representatives = SkillCatalog.forClass(heroClass)
                .filter { it.unlockLevel == 5 }
                .map(::classLayeredAssetSpec)
            assertEquals("$heroClass secondary coverage", 5, representatives.map { it.secondary }.distinct().size)
            assertEquals("$heroClass residual coverage", 5, representatives.map { it.residual }.distinct().size)
            assertEquals("$heroClass candidate debris", 5, representatives.map { it.debrisPoint }.distinct().size)
            assertTrue("$heroClass debris family drift", representatives.all {
                setOf(it.debrisPoint, it.debrisFracture, it.debrisRing).size == 1
            })
        }
    }

    @Test
    fun `warrior heavy keeps one family pack while growth changes body and finisher density`() {
        val representatives = listOf(5, 25, 45, 65, 85).map { level ->
            SkillCatalog.all.single { it.catalogId == "warrior_t${(level / 5).toString().padStart(2, '0')}_c02" }
        }.map(::classLayeredAssetSpec)
        assertEquals(1, representatives.map { it.impactPoint }.distinct().size)
        assertEquals(1, representatives.map { it.debrisPoint }.distinct().size)
        assertEquals(1, representatives.map { it.residual }.distinct().size)
        assertEquals(1, representatives.map { it.primaryA }.distinct().size)
        assertEquals(1, representatives.map { it.finisherRing }.distinct().size)
        assertTrue(representatives.all { it.primaryA == R.drawable.vfx8_warrior_heavy_primary })
    }

    @Test
    fun `every class uses all four authored choreography paths`() {
        HeroClass.entries.forEach { heroClass ->
            val paths = SkillCatalog.forClass(heroClass).map(::classVfxSignature).map { it.path }.toSet()
            assertEquals("$heroClass path coverage", ClassVfxPath.entries.toSet(), paths)
        }
    }

    @Test
    fun `authored paths vary timing scale rotation mirror and depth vectors`() {
        val profiles = ClassVfxPath.entries.map(::classVfxPathProfile)
        assertEquals(4, profiles.map { it.primaryLeadMillis to it.primarySettleMillis }.distinct().size)
        assertEquals(4, profiles.map { it.secondaryLeadMillis to it.secondarySettleMillis }.distinct().size)
        assertEquals(4, profiles.map { it.primaryScale }.distinct().size)
        assertEquals(4, profiles.map { it.rotationOffsetDegrees }.distinct().size)
        assertEquals(setOf(-1f, 1f), profiles.map { it.mirror }.toSet())
        assertEquals(4, profiles.map { it.debrisVector }.distinct().size)
        assertEquals(4, profiles.map { it.residualVector }.distinct().size)
    }

    @Test
    fun `rotated authored assets remain inside the combat canvas`() {
        val canvasWidth = 360f
        val canvasHeight = 160f
        listOf(0f, 14f, -35f, 45f, 90f, 135f).forEach { rotation ->
            val frame = safeAuthoredAssetPlacement(
                canvasWidth = canvasWidth,
                canvasHeight = canvasHeight,
                centerX = 8f,
                centerY = 154f,
                width = 330f,
                height = 150f,
                rotationDegrees = rotation,
            )
            val insetX = canvasWidth * 0.035f
            val insetY = canvasHeight * 0.035f
            assertTrue(frame.centerX - frame.rotatedWidth / 2f >= insetX - 0.001f)
            assertTrue(frame.centerX + frame.rotatedWidth / 2f <= canvasWidth - insetX + 0.001f)
            assertTrue(frame.centerY - frame.rotatedHeight / 2f >= insetY - 0.001f)
            assertTrue(frame.centerY + frame.rotatedHeight / 2f <= canvasHeight - insetY + 0.001f)
        }
    }

    @Test
    fun `vertical and outside in authored primaries preserve visible safe travel`() {
        val affectedFlows = setOf(
            SemanticVfxFlow.TOP_TO_BOTTOM,
            SemanticVfxFlow.BOTTOM_TO_TOP,
            SemanticVfxFlow.OUTSIDE_IN,
        )
        SkillCatalog.all.filterNot(::shouldKeepLegacyPrimary).filter {
            semanticVfxPlan(it).flow in affectedFlows
        }.filterNot {
            // Warrior earth art is a planted impact site. Its fragments may travel, but moving
            // the entire ground bitmap turns the floor into a projectile.
            it.heroClass == HeroClass.WARRIOR && it.candidate == 3
        }.forEach { definition ->
            val endpointIndices = skillPresentationHits(definition).map { it.sourceHitIndices.last() }.toSet()
            definition.hitTimingsMillis.forEachIndexed { hitIndex, timing ->
                if (hitIndex !in endpointIndices) return@forEachIndexed
                val primaryFrames = ((timing - 260).coerceAtLeast(0)..timing + 300 step 10).flatMap { elapsed ->
                    authoredClassFramePlan(definition, elapsed, false, 361f, 160f)
                }.filter { it.role == AuthoredLayerRole.PRIMARY && it.hitIndex == hitIndex }
                assertTrue("${definition.catalogId} hit $hitIndex has no primary travel", primaryFrames.size >= 2)
                val xTravel = primaryFrames.maxOf { it.xFraction } - primaryFrames.minOf { it.xFraction }
                val yTravel = primaryFrames.maxOf { it.yFraction } - primaryFrames.minOf { it.yFraction }
                when (semanticVfxPlan(definition).flow) {
                    SemanticVfxFlow.TOP_TO_BOTTOM,
                    SemanticVfxFlow.BOTTOM_TO_TOP,
                    -> assertTrue(
                        "${definition.catalogId} vertical primary froze after safe placement: $yTravel ${primaryFrames.map { it.yFraction }.distinct()}",
                        yTravel >= 0.014f,
                    )
                    SemanticVfxFlow.OUTSIDE_IN -> assertTrue(
                        "${definition.catalogId} outside-in primary froze after safe placement: $xTravel/$yTravel",
                        maxOf(xTravel, yTravel) >= 0.003f,
                    )
                    else -> Unit
                }
            }
        }
    }

    @Test
    fun `authored camera impulses only occupy grouped presentation endpoint windows`() {
        SkillCatalog.all.filterNot(::shouldKeepLegacyPrimary).filter { definition ->
            skillPresentationHits(definition).any { it.sourceHitIndices.size > 1 }
        }.forEach { definition ->
            val endpoints = skillPresentationHits(definition).map { it.timingMillis }
            (0 until SKILL_VFX_END_MILLIS).forEach { elapsed ->
                val camera = skillCameraFrame(elapsed, definition)
                if (camera != SkillCameraFrame()) {
                    assertTrue(
                        "${definition.catalogId} camera leaked at raw hit $elapsed outside $endpoints",
                        endpoints.any { elapsed in (it - 30)..(it + 120) },
                    )
                }
            }
            endpoints.forEach { endpoint ->
                assertTrue(
                    "${definition.catalogId} lost grouped camera endpoint $endpoint",
                    skillCameraFrame(endpoint + 16, definition) != SkillCameraFrame(),
                )
            }
        }
    }

    @Test
    fun `paladin final contact follows candidate grammar`() {
        val expected = listOf(
            ClassImpactLayer.POINT,
            ClassImpactLayer.FRACTURE,
            ClassImpactLayer.FRACTURE,
            ClassImpactLayer.RING,
            ClassImpactLayer.RING,
        )
        SkillCatalog.forClass(HeroClass.PALADIN)
            .filter { it.unlockLevel == 5 }
            .sortedBy { it.candidate }
            .forEachIndexed { index, definition ->
                assertEquals(expected[index], classImpactLayerFor(definition, definition.hitCount - 1))
            }
    }

    @Test
    fun `only the approved warrior slash family keeps its legacy renderer`() {
        SkillCatalog.all.forEach { definition ->
            val keepsLegacy = shouldKeepLegacyPrimary(definition)
            if (keepsLegacy) {
                assertEquals(HeroClass.WARRIOR, definition.heroClass)
                assertTrue(
                    semanticVfxPlan(definition).action in setOf(
                        SemanticVfxAction.CUT,
                        SemanticVfxAction.CROSS_CUT,
                        SemanticVfxAction.FLURRY,
                        SemanticVfxAction.SPIN,
                    ),
                )
            } else if (definition.heroClass != HeroClass.WARRIOR) {
                assertEquals(false, keepsLegacy)
            }
        }
    }

    @Test
    fun `class vfx signatures are unique across each class catalog`() {
        HeroClass.entries.forEach { heroClass ->
            val signatures = SkillCatalog.all.filter { it.heroClass == heroClass }.map { definition ->
                classVfxSignature(definition).let { signature ->
                    listOf(signature.candidate, signature.growthBand, signature.tierVariant, signature.path.ordinal)
                }
            }
            assertEquals(100, signatures.size)
            assertEquals(100, signatures.distinct().size)
        }
    }
    @Test
    fun `late finishers complete before the attack boundary`() {
        assertTrue(SKILL_VFX_END_MILLIS < SKILL_PRESENTATION_DURATION_MILLIS)
        assertTrue(SKILL_PRESENTATION_DURATION_MILLIS < 1_400)
        SkillCatalog.all.forEach { definition ->
            assertTrue(
                "${definition.catalogId} final afterglow is clipped",
                definition.hitTimingsMillis.last() + 420 <= SKILL_VFX_END_MILLIS,
            )
        }
        SkillCatalog.all.filterNot(::shouldKeepLegacyPrimary).forEach { definition ->
            assertTrue(
                "${definition.catalogId} authored final layer lacks a 60 ms clear frame",
                definition.hitTimingsMillis.last() + 390 <= SKILL_VFX_END_MILLIS - 60,
            )
        }
        assertEquals(50, SKILL_PRESENTATION_DURATION_MILLIS - SKILL_VFX_END_MILLIS)
    }

    @Test
    fun `warrior non slash progression has distinct authored signatures`() {
        val nonSlash = SkillCatalog.forClass(HeroClass.WARRIOR)
            .mapNotNull(::warriorNonSlashSignature)
        assertEquals(60, nonSlash.size)
        assertEquals(WarriorNonSlashFamily.values().toSet(), nonSlash.map { it.family }.toSet())
        nonSlash.groupBy { it.family }.forEach { (family, signatures) ->
            val requiredRoutes = minOf(3, signatures.size)
            val requiredBands = minOf(4, signatures.map { it.intensityBand }.toSet().size)
            assertTrue("$family lacks route variety", signatures.map { it.routeVariant }.toSet().size >= requiredRoutes)
            assertTrue("$family lacks level hierarchy", signatures.map { it.intensityBand }.toSet().size >= requiredBands)
        }
        assertTrue(nonSlash.filter { it.intensityBand >= 4 }.all { it.residualEndMillis >= 700 })
    }

    @Test
    fun `print complete skill visual audit matrix`() {
        if (System.getenv("ALARMQUEST_SKILL_AUDIT") != "true") return
        println("SKILL_AUDIT|catalogId|class|level|name|hits|motion|timing|element|finisher|action|flow|archetype|resource|intensity|layers|candidate|classPrimary|nativeAxis|targetAxis|rotation|axisAligned")
        SkillCatalog.all.forEach { definition ->
            val plan = semanticVfxPlan(definition)
            val nativeAxis = classPrimaryAxis(definition.heroClass, definition.candidate).nativeDegrees
            val targetAxis = classPrimaryMotion(definition, 0).targetDegrees
            val rotation = classPrimaryRotationDegrees(definition, 0)
            val aligned = if (nativeAxis == null || targetAxis == null) {
                rotation == 0f
            } else {
                kotlin.math.abs(
                    (nativeAxis + rotation).mod(360f) - targetAxis.mod(360f),
                ) < 0.0001f
            }
            println(
                listOf(
                    "SKILL_AUDIT",
                    definition.catalogId,
                    definition.heroClass.name,
                    definition.unlockLevel,
                    definition.name,
                    definition.hitCount,
                    definition.motion.name,
                    definition.timingProfile.name,
                    definition.element.name,
                    definition.finisher.name,
                    plan.action.name,
                    plan.flow.name,
                    plan.recipe.archetype.name,
                    plan.recipe.index,
                    definition.intensityTier,
                    modularLayerCount(definition, isFinal = true),
                    definition.candidate,
                    classPrimaryResource(definition),
                    nativeAxis ?: "RADIAL",
                    targetAxis ?: "RADIAL",
                    rotation,
                    aligned,
                ).joinToString("|"),
            )
        }
    }

    @Test
    fun `authoritative authored planner exposes complete web export rows`() {
        val definition = SkillCatalog.all.first {
            !shouldKeepLegacyPrimary(it) && it.unlockLevel >= 85
        }
        val elapsed = definition.hitTimingsMillis.last() + 110
        val frames = authoredClassFramePlan(
            definition = definition,
            elapsedMillis = elapsed,
            reducedMotion = false,
            viewportWidth = 360f,
            viewportHeight = 180f,
        )

        assertTrue(frames.isNotEmpty())
        assertEquals(17, AUTHORED_FRAME_PSV_HEADER.split('|').size)
        frames.forEach { frame ->
            assertEquals(17, frame.toPsv().split('|').size)
            assertTrue(frame.assetId != 0)
            assertTrue(frame.xFraction in -2f..3f)
            assertTrue(frame.yFraction in -2f..3f)
            assertTrue(frame.widthFraction > 0f)
            assertTrue(frame.heightFraction > 0f)
            assertTrue(frame.alpha in 0f..1f)
            assertTrue(frame.reveal in 0f..1f)
            assertTrue(frame.startMillis < frame.endMillis)
            assertTrue(frame.endMillis <= SKILL_VFX_END_MILLIS)
        }
    }

    @Test
    fun `all forty legacy warrior skills expose an exact export branch and frames`() {
        val legacy = SkillCatalog.all.filter(::shouldKeepLegacyPrimary)
        val runtimeAssets = legacyWarriorRuntimeAssetIds().toSet()
        assertEquals(40, legacy.size)
        legacy.forEach { definition ->
            val descriptor = legacyWarriorDescriptor(definition)
            assertTrue("${definition.catalogId} has no branch key", descriptor != null)
            assertTrue(descriptor!!.branchKey.endsWith(definition.catalogId))
            assertTrue("${definition.catalogId} has no primary asset", descriptor!!.primaryAssetId != 0)
            assertTrue(
                "${definition.catalogId} never exports a frame",
                (0..SKILL_PRESENTATION_DURATION_MILLIS step 10).any { elapsed ->
                    legacyWarriorFramePlan(definition, elapsed, false).isNotEmpty()
                },
            )
            val plannedAssets = (0..SKILL_PRESENTATION_DURATION_MILLIS step 10)
                .flatMap { elapsed -> legacyWarriorFramePlan(definition, elapsed, false) }
                .map { it.assetId }
                .toSet()
            assertTrue(
                "${definition.catalogId} emits assets missing from the runtime preload: " +
                    "${plannedAssets - runtimeAssets}",
                runtimeAssets.containsAll(plannedAssets),
            )
        }
        assertEquals(40, legacy.mapNotNull(::legacyWarriorDescriptor).map { it.branchKey }.distinct().size)
    }

    @Test
    fun `every authored frame is available to the Compose runtime preload`() {
        val authored = SkillCatalog.all.filterNot(::shouldKeepLegacyPrimary)
        assertEquals(560, authored.size)
        authored.forEach { definition ->
            val runtimeAssets = (
                classLayeredAssetSpec(definition).authoredRuntimeAssetIds() +
                    if (isCreationEarthquake(definition)) creationEarthquakeSpriteAssetIds else intArrayOf()
                ).toSet()
            listOf(false, true).forEach { reducedMotion ->
                val sampleTimes = (
                    (0..SKILL_PRESENTATION_DURATION_MILLIS step 10) + definition.hitTimingsMillis
                ).distinct()
                val plannedAssets = sampleTimes
                    .flatMap { elapsed ->
                        authoredRenderableFramePlan(definition, elapsed, reducedMotion, 360f, 180f)
                    }
                    .map { it.assetId }
                    .toSet()
                assertTrue(
                    "${definition.catalogId} emits assets missing from the Compose preload: " +
                        "${plannedAssets - runtimeAssets}",
                    runtimeAssets.containsAll(plannedAssets),
                )
            }
        }
    }

    @Test
    fun `all remaining class hits expose contact exactly on the damage landmark`() {
        val remaining = SkillCatalog.all.filter { it.heroClass != HeroClass.WARRIOR }
        assertEquals(500, remaining.size)
        remaining.forEach { definition ->
            definition.hitTimingsMillis.forEachIndexed { hitIndex, hitMillis ->
                val frames = authoredClassFramePlan(definition, hitMillis, false, 360f, 180f)
                assertTrue(
                    "${definition.catalogId} hit $hitIndex has no contact at $hitMillis ms",
                    frames.any { it.role == AuthoredLayerRole.CONTACT && it.hitIndex == hitIndex },
                )
            }
        }
    }

    @Test
    fun `remaining classes use candidate owned rhythms and never exceed four planes`() {
        val remaining = SkillCatalog.all.filter { it.heroClass != HeroClass.WARRIOR }
        HeroClass.entries.filterNot { it == HeroClass.WARRIOR }.forEach { heroClass ->
            val representatives = SkillCatalog.forClass(heroClass).filter { it.unlockLevel == 100 }
            assertEquals(5, representatives.size)
            assertEquals(
                "$heroClass candidate timelines collapsed",
                5,
                representatives.map { definition ->
                    listOf(
                        nonWarriorCandidateRoleTimeline(definition, AuthoredLayerRole.SECONDARY),
                        nonWarriorCandidateRoleTimeline(definition, AuthoredLayerRole.CONTACT),
                        nonWarriorCandidateRoleTimeline(definition, AuthoredLayerRole.RESIDUAL),
                    )
                }.distinct().size,
            )
            assertEquals(
                "$heroClass contact beats collapsed",
                5,
                representatives.map { definition ->
                    checkNotNull(nonWarriorCandidateRoleTimeline(definition, AuthoredLayerRole.CONTACT))
                        .let { it.holdEndMillis to it.endMillis }
                }.distinct().size,
            )
        }
        remaining.forEach { definition ->
            assertEquals(4, phaseCompositionBudget(definition).concurrentLimit)
            (0 until SKILL_VFX_END_MILLIS step 10).forEach { elapsed ->
                val frames = authoredClassFramePlan(definition, elapsed, false, 360f, 180f)
                assertTrue(
                    "${definition.catalogId} stacks ${frames.size} planes at $elapsed ms",
                    frames.size <= 4,
                )
            }
        }
    }

    @Test
    fun `warrior families keep their approved primary recipe and strike anchor`() {
        val viewportWidth = 361f
        val viewportHeight = 160f
        val expectedPrimary = mapOf(
            0 to R.drawable.vfx8_warrior_slash_primary,
            1 to R.drawable.vfx8_warrior_heavy_primary,
            2 to R.drawable.vfx8_warrior_charge_primary,
            3 to R.drawable.vfx8_warrior_earth_primary,
        )
        val warrior = SkillCatalog.forClass(HeroClass.WARRIOR)
        assertEquals(100, warrior.size)
        warrior.forEach { definition ->
            definition.hitTimingsMillis.forEachIndexed { hitIndex, hitMillis ->
                val frames = if (shouldKeepLegacyPrimary(definition)) {
                    legacyWarriorFramePlan(definition, hitMillis, false, viewportWidth, viewportHeight)
                } else {
                    authoredClassFramePlan(definition, hitMillis, false, viewportWidth, viewportHeight)
                }
                val primary = checkNotNull(frames.singleOrNull {
                    it.role == AuthoredLayerRole.PRIMARY && it.hitIndex == hitIndex
                }) { "${definition.catalogId} hit $hitIndex has no single primary" }
                if (definition.candidate == 4) {
                    val recipe = semanticHitChoreography(definition, hitIndex).recipe
                    val expectedFlurryAsset = if (
                        legacyWarriorDescriptor(definition)?.branch == LegacyWarriorBranch.CONTINUOUS_SLASH
                    ) {
                        R.drawable.vfx_warrior_01
                    } else {
                        legacyWarriorFlurryResource(recipe.index)
                    }
                    assertEquals(
                        "${definition.catalogId} lost its reviewed multi-slash art",
                        expectedFlurryAsset,
                        primary.assetId,
                    )
                    assertTrue(
                        "${definition.catalogId} mixed the replacement contact into the restored flurry",
                        frames.none { it.role == AuthoredLayerRole.CONTACT && it.hitIndex == hitIndex },
                    )
                    return@forEachIndexed
                }
                val contact = checkNotNull(frames.singleOrNull {
                    it.role == AuthoredLayerRole.CONTACT && it.hitIndex == hitIndex
                }) { "${definition.catalogId} hit $hitIndex has no single contact" }
                assertEquals("${definition.catalogId} mixes a primary art family", expectedPrimary[definition.candidate], primary.assetId)
                val primaryStrike = warriorResolvedStrikePoint(primary, viewportWidth, viewportHeight)
                val contactStrike = warriorResolvedStrikePoint(contact, viewportWidth, viewportHeight)
                val gapPixels = kotlin.math.hypot(
                    (primaryStrike.x - contactStrike.x) * viewportWidth,
                    (primaryStrike.y - contactStrike.y) * viewportHeight,
                )
                assertTrue("${definition.catalogId} hit $hitIndex detaches by ${gapPixels}px", gapPixels <= 8f)
            }
        }
    }

    @Test
    fun `warrior directional bodies stay centered and ground bodies erupt on one anchor`() {
        val viewportWidth = 361f
        val viewportHeight = 160f
        assertEquals(.494f, warriorStrikeAnchor(R.drawable.vfx8_warrior_slash_primary).x, .0001f)
        assertEquals(.465f, warriorStrikeAnchor(R.drawable.vfx8_warrior_charge_primary).x, .0001f)
        assertEquals(.512f, warriorStrikeAnchor(R.drawable.vfx6_warrior_earth_contact).x, .0001f)
        assertEquals(.713f, warriorStrikeAnchor(R.drawable.vfx6_warrior_earth_contact).y, .0001f)

        val warrior = SkillCatalog.forClass(HeroClass.WARRIOR)
        warrior.filter { it.candidate in 0..3 }.forEach { definition ->
            val impactAt = definition.hitTimingsMillis.last()
            val frames = if (shouldKeepLegacyPrimary(definition)) {
                legacyWarriorFramePlan(definition, impactAt, false, viewportWidth, viewportHeight)
            } else {
                authoredClassFramePlan(definition, impactAt, false, viewportWidth, viewportHeight)
            }
            val primary = checkNotNull(frames.singleOrNull {
                it.role == AuthoredLayerRole.PRIMARY && it.hitIndex == definition.hitTimingsMillis.lastIndex
            })
            val opticalCenter = warriorResolvedStrikePoint(primary, viewportWidth, viewportHeight)
            assertEquals("${definition.catalogId} body is not horizontally centered", .50f, opticalCenter.x, .012f)
        }

        warrior.filter { it.candidate == 2 }.forEach { definition ->
            (0 until SKILL_VFX_END_MILLIS step 10).forEach { elapsed ->
                authoredClassFramePlan(definition, elapsed, false, viewportWidth, viewportHeight)
                    .filter { it.role in setOf(
                        AuthoredLayerRole.SECONDARY,
                        AuthoredLayerRole.PRIMARY,
                        AuthoredLayerRole.CONTACT,
                        AuthoredLayerRole.DEBRIS,
                        AuthoredLayerRole.RESIDUAL,
                    ) }
                    .forEach { frame ->
                        assertEquals("${definition.catalogId} ${frame.role} is mirrored backward", 1f, frame.mirror, .0001f)
                    }
            }
        }

        warrior.filter { it.candidate == 3 }.forEach { definition ->
            val primaryCenters = (0 until SKILL_VFX_END_MILLIS step 10).mapNotNull { elapsed ->
                authoredClassFramePlan(definition, elapsed, false, viewportWidth, viewportHeight)
                    .singleOrNull { it.role == AuthoredLayerRole.PRIMARY }
                    ?.let { Triple(elapsed, it.xFraction, it.yFraction) }
            }
            assertTrue("${definition.catalogId} has no earth body frames", primaryCenters.isNotEmpty())
            assertTrue(
                "${definition.catalogId} earth body drifts horizontally",
                primaryCenters.maxOf { it.second } - primaryCenters.minOf { it.second } <= .0001f,
            )
            val impactAt = definition.hitTimingsMillis.last()
            val approach = primaryCenters.first { it.first < impactAt }
            val impact = primaryCenters.minBy { kotlin.math.abs(it.first - impactAt) }
            assertTrue(
                "${definition.catalogId} earth body no longer bursts upward: ${approach.third} -> ${impact.third}",
                approach.third - impact.third >= .10f,
            )
        }
    }

    @Test
    fun `single slash family descends diagonally from above into contact`() {
        val viewportWidth = 361f
        val viewportHeight = 160f
        SkillCatalog.all.filter {
            legacyWarriorDescriptor(it)?.branch in setOf(
                LegacyWarriorBranch.STEEL_SLASH,
                LegacyWarriorBranch.SINGLE_SLASH,
            )
        }.forEach { definition ->
            val hit = definition.hitTimingsMillis.single()
            val approachFrame = (0 until hit step 10).firstNotNullOf { elapsed ->
                legacyWarriorFramePlan(
                    definition,
                    elapsed,
                    false,
                    viewportWidth,
                    viewportHeight,
                ).singleOrNull { it.role == AuthoredLayerRole.PRIMARY }
            }
            val contactFrame = legacyWarriorFramePlan(
                definition,
                hit,
                false,
                viewportWidth,
                viewportHeight,
            ).single { it.role == AuthoredLayerRole.PRIMARY }
            val approach = warriorResolvedStrikePoint(approachFrame, viewportWidth, viewportHeight)
            val contact = warriorResolvedStrikePoint(contactFrame, viewportWidth, viewportHeight)
            assertEquals("${definition.catalogId} lost descending mirror", -1f, contactFrame.mirror, .0001f)
            assertTrue("${definition.catalogId} does not travel right", contact.x - approach.x >= .055f)
            assertTrue("${definition.catalogId} does not travel down", contact.y - approach.y >= .06f)
        }
    }

    @Test
    fun `legacy warrior branch contract covers both exact single and combo recipes`() {
        val counts = SkillCatalog.all
            .mapNotNull(::legacyWarriorDescriptor)
            .groupingBy { it.branch }
            .eachCount()
        assertEquals(1, counts[LegacyWarriorBranch.STEEL_SLASH])
        assertEquals(1, counts[LegacyWarriorBranch.CONTINUOUS_SLASH])
        assertEquals(19, counts[LegacyWarriorBranch.SINGLE_SLASH])
        assertEquals(19, counts[LegacyWarriorBranch.COMBO_SLASH])

        SkillCatalog.all.filter(::shouldKeepLegacyPrimary).forEach { definition ->
            val frames = (0..SKILL_PRESENTATION_DURATION_MILLIS step 10).flatMap { elapsed ->
                legacyWarriorFramePlan(definition, elapsed, false, 360f, 180f)
            }
            assertTrue("${definition.catalogId} has no exact primary frames", frames.isNotEmpty())
            assertTrue(frames.any { it.role == AuthoredLayerRole.PRIMARY })
            if (definition.candidate == 4) {
                assertTrue(frames.all {
                    it.drawMode in setOf(
                        AuthoredAssetDrawMode.LEGACY_UNTINTED,
                        AuthoredAssetDrawMode.LEGACY_TINTED,
                    )
                })
                assertTrue(frames.all { it.role == AuthoredLayerRole.PRIMARY })
            } else {
                assertTrue(frames.all { it.drawMode == AuthoredAssetDrawMode.AUTHORED_SCREEN })
                assertTrue(frames.any { it.role == AuthoredLayerRole.CONTACT })
            }
            assertTrue(frames.all { it.assetId in legacyWarriorRuntimeAssetIds() })
        }
    }

    @Test
    fun `legacy warrior exact frame export is golden locked`() {
        val digest = MessageDigest.getInstance("SHA-256")
        val drawableNames = drawableEntryNames()
        SkillCatalog.all.filter(::shouldKeepLegacyPrimary).forEach { definition ->
            listOf(false, true).forEach { reducedMotion ->
                (0..SKILL_PRESENTATION_DURATION_MILLIS step 10).forEach { elapsed ->
                    legacyWarriorFramePlan(definition, elapsed, reducedMotion, 360f, 180f).forEach { frame ->
                        val resourceName = checkNotNull(drawableNames[frame.assetId])
                        val stableFrame = frame.copy(safeAlphaCap = null).toPsv()
                            .replace("||${frame.drawMode.name}|", "|${frame.drawMode.name}|")
                            .replaceFirst("|${frame.assetId}|", "|$resourceName|")
                        digest.update("${definition.catalogId}|$elapsed|$reducedMotion|$stableFrame\n".toByteArray())
                    }
                }
            }
        }
        val fingerprint = digest.digest().joinToString("") { "%02x".format(it) }
        // Name-based golden: locks reviewed single slashes and the restored legacy flurry recipe.
        assertEquals("eb9696c648614cdebd2b1166f9efb06bebe32fab039f3a2b168fb267243d8373", fingerprint)
    }

    @Test
    fun `legacy authored windows finish fully and leave the clean frame`() {
        val highSingles = SkillCatalog.all.filter {
            legacyWarriorDescriptor(it)?.branch == LegacyWarriorBranch.SINGLE_SLASH && it.unlockLevel >= 75
        }
        assertTrue(highSingles.isNotEmpty())
        assertTrue(highSingles.all { definition ->
            legacyWarriorFramePlan(definition, definition.hitTimingsMillis.single(), false).isNotEmpty()
        })
        SkillCatalog.all.filter(::shouldKeepLegacyPrimary).forEach { definition ->
            assertTrue(legacyWarriorFramePlan(definition, SKILL_VFX_END_MILLIS, false).isEmpty())
            assertTrue(legacyWarriorFramePlan(definition, SKILL_VFX_END_MILLIS, true).isEmpty())
            val maxReduced = (0 until SKILL_VFX_END_MILLIS step 10).maxOf { elapsed ->
                legacyWarriorFramePlan(definition, elapsed, true).size
            }
            assertTrue("${definition.catalogId} stacks $maxReduced reduced layers", maxReduced <= 2)
        }
    }

    @Test
    fun `legacy slash frames preserve raw full bleed recipe`() {
        val viewportWidth = 361f
        val viewportHeight = 160f
        var fullBleedFrames = 0
        SkillCatalog.all.filter(::shouldKeepLegacyPrimary).forEach { definition ->
            (0 until SKILL_VFX_END_MILLIS step 10).forEach { elapsed ->
                legacyWarriorFramePlan(definition, elapsed, false, viewportWidth, viewportHeight).forEach { frame ->
                    if (legacyWarriorDescriptor(definition)?.branch == LegacyWarriorBranch.STEEL_SLASH ||
                        legacyWarriorDescriptor(definition)?.branch == LegacyWarriorBranch.CONTINUOUS_SLASH
                    ) return@forEach
                    val width = frame.widthFraction * viewportWidth
                    val height = frame.heightFraction * viewportHeight
                    val radians = Math.toRadians(frame.rotationDegrees.toDouble())
                    val rotatedWidth = kotlin.math.abs(kotlin.math.cos(radians)).toFloat() * width +
                        kotlin.math.abs(kotlin.math.sin(radians)).toFloat() * height
                    val rotatedHeight = kotlin.math.abs(kotlin.math.sin(radians)).toFloat() * width +
                        kotlin.math.abs(kotlin.math.cos(radians)).toFloat() * height
                    val centerX = frame.xFraction * viewportWidth
                    val centerY = frame.yFraction * viewportHeight
                    if (
                        centerX - rotatedWidth / 2f < 0f || centerX + rotatedWidth / 2f > viewportWidth ||
                        centerY - rotatedHeight / 2f < 0f || centerY + rotatedHeight / 2f > viewportHeight
                    ) {
                        fullBleedFrames += 1
                    }
                }
            }
        }
        assertTrue("legacy slash recipes no longer exceed the viewport", fullBleedFrames > 0)
    }

    @Test
    fun `legacy level one hundred finish exceeds level fifty full bleed`() {
        val viewportWidth = 361f
        val viewportHeight = 160f
        fun maximumOutsideBleed(definition: com.alarmquest.engine.SkillDefinition): Float =
            (0 until SKILL_VFX_END_MILLIS step 10).flatMap { elapsed ->
                legacyWarriorFramePlan(definition, elapsed, false, viewportWidth, viewportHeight)
            }.maxOf { frame ->
                val radians = Math.toRadians(frame.rotationDegrees.toDouble())
                val width = frame.widthFraction * viewportWidth
                val height = frame.heightFraction * viewportHeight
                val rotatedWidth = kotlin.math.abs(kotlin.math.cos(radians)).toFloat() * width +
                    kotlin.math.abs(kotlin.math.sin(radians)).toFloat() * height
                val rotatedHeight = kotlin.math.abs(kotlin.math.sin(radians)).toFloat() * width +
                    kotlin.math.abs(kotlin.math.cos(radians)).toFloat() * height
                maxOf(
                    -(frame.xFraction - rotatedWidth / viewportWidth / 2f),
                    frame.xFraction + rotatedWidth / viewportWidth / 2f - 1f,
                    -(frame.yFraction - rotatedHeight / viewportHeight / 2f),
                    frame.yFraction + rotatedHeight / viewportHeight / 2f - 1f,
                    0f,
                )
            }
        listOf(LegacyWarriorBranch.SINGLE_SLASH, LegacyWarriorBranch.COMBO_SLASH).forEach { branch ->
            val levelFifty = SkillCatalog.all.single {
                it.unlockLevel == 50 && legacyWarriorDescriptor(it)?.branch == branch
            }
            val levelHundred = SkillCatalog.all.single {
                it.unlockLevel == 100 && legacyWarriorDescriptor(it)?.branch == branch
            }
            assertTrue(
                "$branch level 100 no longer exceeds level 50",
                maximumOutsideBleed(levelHundred) > maximumOutsideBleed(levelFifty),
            )
        }
    }

    @Test
    fun `every exported raster id resolves to an exact drawable entry name`() {
        val names = drawableEntryNames()
        val exportedIds = SkillCatalog.all.flatMap { definition ->
            if (legacyWarriorDescriptor(definition) != null) {
                legacyWarriorRuntimeAssetIds().asIterable()
            } else {
                classLayeredAssetSpec(definition).authoredRuntimeAssetIds().asIterable()
            }
        }.toSet()
        assertTrue(exportedIds.isNotEmpty())
        assertTrue(exportedIds.all(names::containsKey))
    }

    @Test
    fun `authored final compositions match their real four to eight role contract`() {
        SkillCatalog.all.filterNot(::shouldKeepLegacyPrimary).forEach { definition ->
            val roles = authoredCompositionRoles(definition, isFinal = true)
            assertEquals("${definition.catalogId} count drift", roles.size, modularLayerCount(definition, true))
            assertTrue("${definition.catalogId} is outside the authored budget", roles.size in 4..8)
            assertEquals("${definition.catalogId} duplicates a role", roles.size, roles.distinct().size)
            assertEquals(2, authoredCompositionRoles(definition, isFinal = false).size)
        }
    }

    @Test
    fun `growth bands add composition instead of scaling one central bitmap`() {
        val low = SkillCatalog.find("mage_t01_c01")!!
        val middle = SkillCatalog.find("mage_t05_c01")!!
        val legendary = SkillCatalog.find("mage_t17_c01")!!

        assertEquals(4, authoredCompositionRoles(low, true).size)
        assertEquals(5, authoredCompositionRoles(middle, true).size)
        assertEquals(6, authoredCompositionRoles(legendary, true).size)
        assertTrue(AuthoredLayerRole.RESIDUAL !in authoredCompositionRoles(low, true))
        assertTrue(AuthoredLayerRole.RESIDUAL in authoredCompositionRoles(middle, true))
        assertTrue(AuthoredLayerRole.FINISHER_RING !in authoredCompositionRoles(legendary, true))
        assertTrue(AuthoredLayerRole.FINISHER_ECHO in authoredCompositionRoles(legendary, true))
    }

    @Test
    fun `long converge anticipation reaches the renderer before minus one hundred fifty`() {
        val definition = SkillCatalog.all.first {
            !shouldKeepLegacyPrimary(it) &&
                warriorNonSlashColumn(it) == null &&
                classVfxSignature(it).path == ClassVfxPath.CONVERGE &&
                it.hitTimingsMillis.last() >= 240
        }
        val elapsed = definition.hitTimingsMillis.last() - 200
        val frames = authoredClassFramePlan(definition, elapsed, false, 360f, 180f)

        assertTrue(frames.any { it.hitIndex == definition.hitCount - 1 && it.role == AuthoredLayerRole.SECONDARY })
    }

    @Test
    fun `warrior non slash columns exclude generic accent planes and use reviewed family assets`() {
        val definitions = SkillCatalog.forClass(HeroClass.WARRIOR).filterNot(::shouldKeepLegacyPrimary)
        assertEquals(60, definitions.size)
        definitions.forEach { definition ->
            assertTrue(
                "${definition.catalogId} still renders the retired generic accent plane",
                AuthoredLayerRole.WARRIOR_ACCENT !in authoredCompositionRoles(definition, true),
            )
        }
        val heavy = classLayeredAssetSpec(checkNotNull(SkillCatalog.find("warrior_t20_c02")))
        val charge = classLayeredAssetSpec(checkNotNull(SkillCatalog.find("warrior_t20_c03")))
        val earth = classLayeredAssetSpec(checkNotNull(SkillCatalog.find("warrior_t20_c04")))
        assertEquals(R.drawable.vfx6_warrior_heavy_echo, heavy.residual)
        assertEquals(R.drawable.vfx7_warrior_charge_anticipation, charge.secondary)
        assertEquals(R.drawable.vfx7_warrior_charge_contact, charge.impactPoint)
        assertEquals(R.drawable.vfx7_warrior_charge_debris, charge.debrisPoint)
        assertEquals(R.drawable.vfx7_warrior_charge_echo, charge.residual)
        assertEquals(R.drawable.vfx7_warrior_earth_anticipation, earth.secondary)
        assertEquals(R.drawable.vfx2_warrior_earth_residual, earth.residual)
    }

    @Test
    fun `reduced motion globally caps adjacent hit layers at two`() {
        SkillCatalog.all.filterNot(::shouldKeepLegacyPrimary).forEach { definition ->
            var elapsed = 0
            while (elapsed < SKILL_VFX_END_MILLIS) {
                val frames = authoredClassFramePlan(definition, elapsed, true, 360f, 180f)
                assertTrue("${definition.catalogId} stacks ${frames.size} layers at $elapsed", frames.size <= 2)
                assertTrue(frames.all { it.rotationDegrees == 0f && it.mirror == 1f })
                elapsed += 20
            }
        }
    }

    @Test
    fun `path profiles separate position rotation scale and phase across authored roles`() {
        ClassVfxPath.entries.forEach { path ->
            val definition = SkillCatalog.all.first {
                !shouldKeepLegacyPrimary(it) &&
                    it.unlockLevel >= 45 &&
                    classVfxSignature(it).path == path &&
                    it.hitTimingsMillis.last() <= 820
            }
            val timing = definition.hitTimingsMillis.last()
            val sampled = (-220..260 step 20).flatMap { local ->
                authoredClassFramePlan(definition, timing + local, false, 360f, 180f)
            }.filter { it.hitIndex == definition.hitCount - 1 }
            val representatives = sampled.groupBy { it.role }.mapValues { (_, frames) -> frames.first() }

            authoredCompositionRoles(definition, true).forEach { role ->
                assertTrue("${definition.catalogId} never draws $role", role in representatives)
            }
            assertTrue(representatives.values.map { it.startMillis to it.endMillis }.distinct().size >= 4)
            assertTrue(representatives.values.map { it.xFraction to it.yFraction }.distinct().size >= 3)
            assertTrue(representatives.values.map { it.widthFraction }.distinct().size >= 3)
            assertTrue(representatives.values.map { it.rotationDegrees }.distinct().size >= 3)
        }
    }

    @Test
    fun `all one hundred fifty class candidate band contracts are stable and replaceable`() {
        val keys = SkillCatalog.all.map(::classCandidateBandKey).toSet()
        assertEquals(150, keys.size)
        assertEquals(150, keys.map { it.stableId }.distinct().size)
        assertTrue(keys.all { it.candidate in 0..4 && it.growthBand in 0..4 })

        val sentinel = 987_654_321
        val resolver = ClassBandPrimaryResolver { key, fallback ->
            if (key.heroClass == HeroClass.MAGE && key.candidate == 2 && key.growthBand == 3) sentinel else fallback
        }
        val target = SkillCatalog.all.first {
            it.heroClass == HeroClass.MAGE && it.candidate == 2 && classVfxSignature(it).growthBand == 3
        }
        assertEquals(sentinel, classBandPrimaryResource(target, resolver))
        val ordinary = SkillCatalog.all.first {
            it.heroClass == HeroClass.MAGE && it.candidate == 2 && classVfxSignature(it).growthBand == 3
        }
        assertEquals(
            AuthoredClassBandPrimaryResolver.resolve(
                classCandidateBandKey(ordinary),
                classPrimaryResource(ordinary),
            ),
            classBandPrimaryResource(ordinary),
        )
    }

    @Test
    fun `candidate band choreography provides one hundred fifty spatial rhythms`() {
        val byKey = SkillCatalog.all.associate { classCandidateBandKey(it) to classBandChoreography(it) }
        assertEquals(150, byKey.size)
        assertEquals(150, byKey.values.map { it.stableId }.distinct().size)
        HeroClass.entries.forEach { heroClass ->
            (0..4).forEach { candidate ->
                val sequence = (0..4).map { band ->
                    byKey.getValue(ClassCandidateBandKey(heroClass, candidate, band))
                }
                assertTrue(sequence.zipWithNext().all { (before, after) -> before.primaryScale < after.primaryScale })
                assertTrue(sequence.zipWithNext().all { (before, after) -> before.debrisSpread < after.debrisSpread })
                assertTrue(sequence.map { it.targetOffset }.distinct().size >= 4)
            }
        }
    }

    @Test
    fun `low mid and high authored bands expose four five and six plus real phase roles`() {
        val representatives = listOf(
            SkillCatalog.find("mage_t01_c01")!! to 4,
            SkillCatalog.find("mage_t05_c01")!! to 5,
            SkillCatalog.find("mage_t13_c01")!! to 5,
            SkillCatalog.find("mage_t17_c01")!! to 6,
        )
        representatives.forEach { (definition, expectedRoles) ->
            val finalTiming = definition.hitTimingsMillis.last()
            val frames = (-260..500 step 10).flatMap { local ->
                authoredClassFramePlan(definition, finalTiming + local, false, 360f, 180f)
            }.filter { it.hitIndex == definition.hitTimingsMillis.lastIndex }
            assertEquals(expectedRoles, frames.map { it.role }.distinct().size)
            assertTrue(frames.map { it.startMillis to it.endMillis }.distinct().size >= expectedRoles - 1)
        }
    }

    @Test
    fun `authored frames preserve full source alpha for topmost damage overlay`() {
        SkillCatalog.all.filterNot(::shouldKeepLegacyPrimary).forEach { definition ->
            val last = definition.hitTimingsMillis.last()
            val frames = (last - 260..minOf(last + 500, SKILL_VFX_END_MILLIS - 1) step 20).flatMap { elapsed ->
                authoredRenderableFramePlan(definition, elapsed, false, 360f, 180f)
            }
            assertTrue("${definition.catalogId} lost all authored frames", frames.any { it.alpha > 0f })
            assertTrue(frames.all { it.alpha in 0f..1f })
            assertTrue(frames.all { it.safeAlphaCap == null })
        }
    }

    @Test
    fun `all high tier authored finishers complete before the clean frame`() {
        SkillCatalog.all.filterNot(::shouldKeepLegacyPrimary).filter { it.unlockLevel >= 65 }.forEach { definition ->
            val finalTiming = definition.hitTimingsMillis.last()
            val tailRoles = (finalTiming..SKILL_VFX_END_MILLIS step 10).flatMap { elapsed ->
                authoredClassFramePlan(definition, elapsed, false, 360f, 180f)
            }.filter { it.hitIndex == definition.hitTimingsMillis.lastIndex }.map { it.role }.toSet()
            if (warriorNonSlashColumn(definition) != null) {
                assertTrue("${definition.catalogId} lost its single family tail", AuthoredLayerRole.RESIDUAL in tailRoles)
                assertTrue("${definition.catalogId} restored the duplicate finisher halo", AuthoredLayerRole.FINISHER_RING !in tailRoles)
            } else {
                assertTrue("${definition.catalogId} restored the generic finisher ring", AuthoredLayerRole.FINISHER_RING !in tailRoles)
                assertTrue("${definition.catalogId} lost its candidate tail", AuthoredLayerRole.RESIDUAL in tailRoles)
            }
            if (classVfxSignature(definition).growthBand >= 4 && warriorNonSlashColumn(definition) == null) {
                assertTrue("${definition.catalogId} lost finisher echo", AuthoredLayerRole.FINISHER_ECHO in tailRoles)
            }
            assertTrue(authoredClassFramePlan(definition, SKILL_VFX_END_MILLIS - 1, false, 360f, 180f).all {
                it.endMillis <= SKILL_VFX_END_MILLIS
            })
            assertTrue(authoredClassFramePlan(definition, SKILL_VFX_END_MILLIS, false, 360f, 180f).isEmpty())
        }
    }

    @Test
    fun `authoritative grouped damage energy and camera cover parity sentinel skills`() {
        listOf("mage_t01_c03", "cleric_t19_c05").forEach { catalogId ->
            val definition = SkillCatalog.find(catalogId)!!
            val groups = skillPresentationHits(definition)
            assertEquals(listOf(260, 430, 560, 740), groups.map { it.timingMillis })
            assertEquals(listOf(15, 17, 18, 50), groups.map { it.weight })
            assertEquals(10_000L, groupedSkillDamage(10_000L, definition).sum())
            groups.forEachIndexed { index, group ->
                val damage = skillDamageFrame(group.timingMillis + 24, definition, 10_000L)
                assertTrue("$catalogId group $index has no damage frame", damage.visible)
                assertEquals(groupedSkillDamage(10_000L, definition)[index], damage.damage)
                val energyBefore = skillEnergyFraction(group.timingMillis, .78f, .16f, definition)
                val energyAfter = skillEnergyFraction(group.timingMillis + 96, .78f, .16f, definition)
                assertTrue("$catalogId group $index gauge did not descend", energyAfter <= energyBefore)
            }
            assertTrue(
                "$catalogId has no camera impulse",
                (0 until SKILL_VFX_END_MILLIS step 10).any { elapsed ->
                    skillCameraFrame(elapsed, definition) != SkillCameraFrame()
                },
            )
        }
    }

    @Test
    fun `authored planner reserves sixty millisecond clean tail without hard cuts`() {
        val authored = SkillCatalog.all.filterNot(::shouldKeepLegacyPrimary)
        authored.forEach { definition ->
            val lastSampleAlpha = authoredClassFramePlan(
                definition,
                SKILL_VFX_LAST_VISIBLE_MILLIS - 10,
                false,
                361f,
                160f,
            ).maxOfOrNull { it.alpha } ?: 0f
            assertTrue(
                "${definition.catalogId} hard-cuts at the clean-tail boundary: $lastSampleAlpha",
                lastSampleAlpha < .10f,
            )
            assertTrue(
                "${definition.catalogId} remains visible in the clean tail",
                (SKILL_VFX_LAST_VISIBLE_MILLIS until SKILL_VFX_END_MILLIS).none { elapsed ->
                    authoredClassFramePlan(definition, elapsed, false, 361f, 160f).isNotEmpty()
                },
            )
            val endingFrames = authoredClassFramePlan(
                definition,
                SKILL_VFX_LAST_VISIBLE_MILLIS - 1,
                false,
                361f,
                160f,
            )
            assertTrue(endingFrames.all { it.endMillis <= SKILL_VFX_LAST_VISIBLE_MILLIS })
        }
    }

    @Test
    fun `normal authored planner grows concurrent composition from four to six layers`() {
        SkillCatalog.all.filterNot(::shouldKeepLegacyPrimary).forEach { definition ->
            val band = classVfxSignature(definition).growthBand
            val expectedLimit = 4 + band.coerceAtMost(2)
            (0 until SKILL_VFX_END_MILLIS step 10).forEach { elapsed ->
                val frames = authoredClassFramePlan(definition, elapsed, false, 361f, 160f)
                assertTrue(
                    "${definition.catalogId} band $band stacks ${frames.size} at $elapsed",
                    frames.size <= expectedLimit,
                )
            }
        }
    }

    @Test
    fun `fierce downward strike reads as a vertical accelerating impact`() {
        val definition = SkillCatalog.all.single { it.catalogId == "warrior_t01_c02" }
        val plan = semanticVfxPlan(definition)

        assertTrue(isFierceDownwardStrike(definition))
        assertEquals(SemanticVfxAction.DESCEND, plan.action)
        assertEquals(SemanticVfxFlow.TOP_TO_BOTTOM, plan.flow)

        val anticipation = fierceDownwardStrikeVfxFrame(300, reducedMotion = false)
        val descent = fierceDownwardStrikeVfxFrame(390, reducedMotion = false)
        val impact = fierceDownwardStrikeVfxFrame(420, reducedMotion = false)
        assertTrue(anticipation.visible)
        assertTrue(descent.reveal > anticipation.reveal)
        assertTrue(descent.centerYFraction > anticipation.centerYFraction)
        assertEquals(1f, impact.reveal, 0.0001f)
        assertEquals(0.38f, impact.centerYFraction, 0.0001f)

        val cameraAtImpact = skillCameraFrame(420, definition)
        assertTrue(cameraAtImpact.translationY > 0f)
        val camera = skillCameraFrame(430, definition)
        assertEquals(0f, camera.translationX, 0.0001f)
        assertTrue(camera.translationY != 0f)

        val reduced = fierceDownwardStrikeVfxFrame(500, reducedMotion = true)
        assertEquals(1f, reduced.reveal, 0.0001f)
        assertEquals(0.82f, reduced.scale, 0.0001f)
        assertEquals(0f, skillCameraFrame(SKILL_PRESENTATION_DURATION_MILLIS, definition).translationY, 0.0001f)
    }

    @Test
    fun `charging thrust accelerates diagonally into the fixed impact point`() {
        val definition = SkillCatalog.all.single { it.catalogId == "warrior_t01_c03" }
        val plan = semanticVfxPlan(definition)

        assertTrue(isChargingThrust(definition))
        assertEquals(SemanticVfxAction.CHARGE, plan.action)
        assertEquals(SemanticVfxFlow.LEFT_TO_RIGHT, plan.flow)

        val anticipation = chargingThrustVfxFrame(300, reducedMotion = false)
        val approach = chargingThrustVfxFrame(390, reducedMotion = false)
        val impact = chargingThrustVfxFrame(420, reducedMotion = false)
        assertTrue(approach.xFraction > anticipation.xFraction)
        assertTrue(approach.yFraction < anticipation.yFraction)
        assertTrue(approach.scale < anticipation.scale)
        assertEquals(0.50f, impact.xFraction, 0.0001f)
        assertEquals(0.60f, impact.yFraction, 0.0001f)

        val cameraAtImpact = skillCameraFrame(420, definition)
        assertTrue(cameraAtImpact.translationX > 0f)
        assertEquals(0f, cameraAtImpact.translationY, 0.0001f)

        val reduced = chargingThrustVfxFrame(500, reducedMotion = true)
        assertEquals(0.50f, reduced.xFraction, 0.0001f)
        assertEquals(0.60f, reduced.yFraction, 0.0001f)
        assertEquals(0.72f, reduced.scale, 0.0001f)
    }

    @Test
    fun `ground impact expands as a low earth fracture instead of a magic ring`() {
        val definition = SkillCatalog.all.single { it.catalogId == "warrior_t01_c04" }
        val plan = semanticVfxPlan(definition)

        assertTrue(isGroundImpact(definition))
        assertEquals(SemanticVfxAction.BURST, plan.action)
        assertEquals(SemanticVfxFlow.CENTER_OUT, plan.flow)

        val warning = groundImpactVfxFrame(360, reducedMotion = false)
        val impact = groundImpactVfxFrame(420, reducedMotion = false)
        assertTrue(warning.visible)
        assertTrue(impact.scale > warning.scale)
        assertTrue(impact.alpha > warning.alpha)

        val authoredAtImpact = authoredClassFramePlan(definition, 420, false, 361f, 160f)
        val primary = authoredAtImpact.single { it.role == AuthoredLayerRole.PRIMARY }
        val contact = authoredAtImpact.single { it.role == AuthoredLayerRole.CONTACT }
        assertTrue(
            "ground contact is still too small",
            contact.widthFraction >= primary.widthFraction * .65f,
        )
        assertTrue(
            "ground contact overwhelms the horizontal terrain width",
            contact.widthFraction <= primary.widthFraction * .92f + .0001f,
        )
        assertTrue(
            "ground contact remains an over-tall detached column",
            contact.heightFraction <= primary.heightFraction * 1.40f,
        )
        assertEquals("ground contact should not tilt off the terrain axis", 0f, contact.rotationDegrees, .0001f)
        val primaryImpact = warriorResolvedStrikePoint(primary, 361f, 160f)
        val contactImpact = warriorResolvedStrikePoint(contact, 361f, 160f)
        assertTrue(
            "ground contact misses the primary collision point: $primaryImpact vs $contactImpact",
            kotlin.math.hypot(primaryImpact.x - contactImpact.x, primaryImpact.y - contactImpact.y) <= .025f,
        )
        val cameraAtImpact = skillCameraFrame(420, definition)
        assertTrue("ground impact has no vertical shake", cameraAtImpact.translationY >= 4f)
        assertTrue("ground impact has no zoom impulse", cameraAtImpact.scale >= 1.015f)

        val reduced = groundImpactVfxFrame(500, reducedMotion = true)
        assertEquals(0.82f, reduced.scale, 0.0001f)
        assertEquals(0.48f, reduced.alpha, 0.0001f)
        assertEquals(SkillCameraFrame(), skillCameraFrame(SKILL_PRESENTATION_DURATION_MILLIS, definition))
    }

    @Test
    fun `crimson combo and blood frenzy use deliberate red tint`() {
        mapOf(
            "warrior_t07_c05" to 0xFFFF342EL,
            "warrior_t10_c05" to 0xFFD5102FL,
        ).forEach { (catalogId, tint) ->
            val definition = checkNotNull(SkillCatalog.find(catalogId))
            val frames = definition.hitTimingsMillis.flatMap { hit ->
                legacyWarriorFramePlan(definition, hit, false, 361f, 160f)
            }
            assertTrue("$catalogId has no visible combo frames", frames.any { it.alpha > .5f })
            assertTrue("$catalogId is not tinted", frames.all { it.drawMode == AuthoredAssetDrawMode.LEGACY_TINTED })
            assertTrue("$catalogId uses the wrong red", frames.all { it.tintArgb == tint })
        }
    }

    @Test
    fun `twelve slash exposes every authored hit instead of only the finisher`() {
        val definition = checkNotNull(SkillCatalog.find("warrior_t16_c05"))
        assertEquals(12, definition.hitTimingsMillis.size)
        definition.hitTimingsMillis.forEachIndexed { hitIndex, hit ->
            val frame = legacyWarriorFramePlan(definition, hit, false, 361f, 160f)
                .single { it.role == AuthoredLayerRole.PRIMARY && it.hitIndex == hitIndex }
            assertTrue("twelve slash hit $hitIndex is transparent", frame.alpha >= .78f)
        }
    }

    @Test
    fun `attack name and damage disappear together when the impact finishes`() {
        assertTrue(attackPresentationVisible(progress = 0f, damage = 55L))
        assertTrue(attackPresentationVisible(progress = 0.999f, damage = 55L))
        assertEquals(false, attackPresentationVisible(progress = 1f, damage = 55L))
        assertEquals(false, attackPresentationVisible(progress = 0.4f, damage = 0L))
    }

    @Test
    fun `only skill attacks show an attack label`() {
        assertEquals(-51, SKILL_LABEL_OFFSET_DP)
        assertEquals(false, attackLabelVisible(true, isSkill = false, attackName = ""))
        assertEquals(false, attackLabelVisible(true, isSkill = false, attackName = "힘껏 내리치기"))
        assertEquals(true, attackLabelVisible(true, isSkill = true, attackName = "강타"))
        assertEquals(false, attackLabelVisible(false, isSkill = true, attackName = "강타"))
    }

    @Test
    fun `attack label fades in holds still and exits before damage`() {
        assertEquals(SKILL_PRESENTATION_DURATION_MILLIS, ATTACK_IMPACT_DURATION_MILLIS)
        assertEquals(0f, attackLabelMotion(0f).alpha)
        assertEquals(1f, attackLabelMotion(0.2f).alpha)
        assertEquals(1f, attackLabelMotion(0.79f).alpha)
        assertTrue(attackLabelMotion(0.9f).alpha < 1f)
        assertEquals(0f, attackLabelMotion(1f).alpha)
    }

    @Test
    fun `damage delays then scales settles holds and exits upward`() {
        val start = damageMotion(0f, isSkill = true)
        val overshoot = damageMotion(0.25f, isSkill = true)
        val held = damageMotion(0.5f, isSkill = true)
        val end = damageMotion(1f, isSkill = true)

        assertEquals(0f, start.alpha)
        assertEquals(0.88f, start.scale)
        assertTrue(overshoot.scale > 1f)
        assertEquals(1f, held.alpha)
        assertEquals(1f, held.scale)
        assertTrue(end.translationY < 0f)
        assertTrue(end.scale < held.scale)
        assertEquals(0f, end.alpha, 0.0001f)
    }

    @Test
    fun `skill impact is stronger than basic impact without moving the text`() {
        assertTrue(damageMotion(0.25f, isSkill = true).scale > damageMotion(0.25f, isSkill = false).scale)
        assertTrue(kotlin.math.abs(backgroundShake(0.03f, isSkill = true)) > kotlin.math.abs(backgroundShake(0.03f, isSkill = false)))
        assertEquals(0f, backgroundShake(0.2f, isSkill = true))
    }

    @Test
    fun `multi hit damage frames preserve the total and never overlap`() {
        SkillCatalog.all.forEach { definition ->
            val hits = skillPresentationHits(definition)
            val damages = groupedSkillDamage(1_337L, definition)

            assertEquals(hits.size, damages.size)
            assertEquals(1_337L, damages.sum())
            hits.forEachIndexed { index, hit ->
                val timing = hit.timingMillis
                val frame = skillDamageFrame(timing, definition, 1_337L)
                assertTrue("${definition.catalogId} group $index is hidden", frame.visible)
                assertEquals(damages[index], frame.damage)
                assertEquals(index == hits.lastIndex, frame.isFinal)
                if (index < hits.lastIndex) {
                    assertTrue(skillDamageFrame(timing + MIN_DAMAGE_READ_MILLIS - 1, definition, 1_337L).visible)
                    assertEquals(
                        false,
                        skillDamageFrame(
                            hits[index + 1].timingMillis - DAMAGE_FRAME_HANDOFF_MILLIS,
                            definition,
                            1_337L,
                        ).visible,
                    )
                }
            }
        }
    }

    @Test
    fun `all six hundred skills preserve authored hits while presentation groups stay readable`() {
        assertEquals(600, SkillCatalog.all.size)
        SkillCatalog.all.forEach { definition ->
            val hits = skillPresentationHits(definition)
            assertEquals(definition.hitTimingsMillis.last(), hits.last().timingMillis)
            assertEquals(definition.hitTimingsMillis.indices.toList(), hits.flatMap { it.sourceHitIndices })
            assertEquals(100, hits.sumOf { it.weight })
            assertTrue(
                "${definition.catalogId} presentation gap is shorter than $MIN_PRESENTATION_HIT_GAP_MILLIS ms",
                hits.zipWithNext().all { (before, after) ->
                    after.timingMillis - before.timingMillis >= MIN_PRESENTATION_HIT_GAP_MILLIS
                },
            )
            hits.forEach { hit ->
                assertEquals(definition.hitTimingsMillis[hit.sourceHitIndices.last()], hit.timingMillis)
            }
        }
    }

    @Test
    fun `known rapid non slash skills group only their unreadable display hits`() {
        val expected = mapOf(
            "ranger_t07_c02" to listOf(listOf(0), listOf(1), listOf(2), listOf(3), listOf(4), listOf(5, 6)),
            "mage_t01_c03" to listOf(listOf(0), listOf(1), listOf(2), listOf(3, 4)),
            "cleric_t19_c05" to listOf(listOf(0), listOf(1), listOf(2), listOf(3, 4)),
        )
        expected.forEach { (catalogId, sourceGroups) ->
            val definition = SkillCatalog.all.single { it.catalogId == catalogId }
            assertEquals(sourceGroups, skillPresentationHits(definition).map { it.sourceHitIndices })
        }
    }

    @Test
    fun `presentation grouping never changes engine damage splitting`() {
        SkillCatalog.all.forEach { definition ->
            val rawDamages = splitSkillDamage(9_973L, definition)
            val groupedDamages = groupedSkillDamage(9_973L, definition)
            assertEquals(definition.hitCount, rawDamages.size)
            assertEquals(rawDamages.sum(), groupedDamages.sum())
            assertEquals(9_973L, groupedDamages.sum())
        }
    }

    @Test
    fun `multi hit energy follows hit timings and snaps exactly to zero`() {
        val definition = SkillCatalog.all.first { it.hitCount == 5 }
        val firstHit = definition.hitTimingsMillis.first()
        val lastHit = definition.hitTimingsMillis.last()

        assertEquals(1f, skillEnergyFraction(firstHit - 1, 1f, 0f, definition))
        assertTrue(skillEnergyFraction(firstHit + 72, 1f, 0f, definition) < 1f)
        assertEquals(0f, skillEnergyFraction(lastHit + 96, 1f, 0f, definition), 0.0001f)
    }

    @Test
    fun `skill label and camera fully reset before the next attack`() {
        val definition = SkillCatalog.all.first { it.hitCount >= 3 }

        assertTrue(skillLabelAlpha(definition.hitTimingsMillis.first(), definition) > 0f)
        assertEquals(0f, skillLabelAlpha(SKILL_VFX_END_MILLIS, definition))
        assertEquals(SkillCameraFrame(), skillCameraFrame(SKILL_PRESENTATION_DURATION_MILLIS, definition))
    }

    @Test
    fun `basic and skill damage share the same fixed center sizing contract`() {
        assertEquals(46, skillDamageFontSize(1, true))
        assertEquals(28, skillDamageFontSize(5, false))
        assertEquals(40, skillDamageFontSize(5, true))
    }

    @Test
    fun `monotonic combat clock ignores animator duration scale and clamps cleanly`() {
        val start = 8_000_000_000L
        assertEquals(0, combatPresentationElapsedMillis(start, start - 1L))
        assertEquals(16, combatPresentationElapsedMillis(start, start + 16_900_000L))
        assertEquals(SKILL_PRESENTATION_DURATION_MILLIS, combatPresentationElapsedMillis(start, start + 9_000_000_000L))
    }

    @Test
    fun `new attack shows pre-hit energy on its first composition frame`() {
        val definition = SkillCatalog.all.first { it.hitCount >= 3 }
        val startEnergy = 0.8f
        val endEnergy = 0.7f
        val firstFrameElapsed = combatPresentationElapsedForCurrentFrame(
            currentActionSequence = 42L,
            observedActionSequence = 41L,
            elapsedMillis = SKILL_PRESENTATION_DURATION_MILLIS,
        )

        assertEquals(0, firstFrameElapsed)
        assertEquals(
            startEnergy,
            skillEnergyFraction(firstFrameElapsed, startEnergy, endEnergy, definition),
            0.0001f,
        )

        val elapsedFrames = buildList {
            add(firstFrameElapsed)
            add(0)
            definition.hitTimingsMillis.forEach { timing ->
                add(timing)
                add(timing + 36)
                add(timing + 96)
            }
        }.sorted()
        val displayedEnergy = elapsedFrames.map { elapsed ->
            skillEnergyFraction(elapsed, startEnergy, endEnergy, definition)
        }
        assertTrue(displayedEnergy.zipWithNext().all { (before, after) -> after <= before })
    }

    @Test
    fun `all six hundred skills keep damage complete while labels remain brief`() {
        assertEquals(600, SkillCatalog.all.size)
        SkillCatalog.all.forEach { definition ->
            val finalEnd = skillFinalDamageEndMillis(definition)
            val finalStart = definition.hitTimingsMillis.last()

            assertTrue("${definition.catalogId} final damage is too short", finalEnd - finalStart >= 380)
            assertTrue(skillDamageFrame(finalEnd - 1, definition, 9_999L).visible)
            assertEquals(false, skillDamageFrame(finalEnd, definition, 9_999L).visible)
            assertEquals(0f, skillLabelAlpha(finalEnd, definition))
            assertTrue(finalEnd <= SKILL_VFX_END_MILLIS)

            val visibleLabelSamples = (0 until SKILL_VFX_END_MILLIS)
                .filter { skillLabelAlpha(it, definition) > 0f }
            assertTrue("${definition.catalogId} label never appears", visibleLabelSamples.isNotEmpty())
            assertTrue(
                "${definition.catalogId} label stays visible too long",
                visibleLabelSamples.last() - visibleLabelSamples.first() < 420,
            )
        }
    }

    @Test
    fun `all catalog presentation frames stay finite in range and fully reset`() {
        SkillCatalog.all.forEach { definition ->
            var elapsed = 0
            while (elapsed <= SKILL_PRESENTATION_DURATION_MILLIS) {
                val damage = skillDamageFrame(elapsed, definition, Long.MAX_VALUE)
                val energy = skillEnergyFraction(elapsed, 1f, 0f, definition)
                val camera = skillCameraFrame(elapsed, definition)
                val label = skillLabelAlpha(elapsed, definition)

                assertTrue("${definition.catalogId} damage alpha", damage.alpha.isFinite() && damage.alpha in 0f..1f)
                assertTrue("${definition.catalogId} damage scale", damage.scale.isFinite() && damage.scale > 0f)
                assertTrue("${definition.catalogId} energy", energy.isFinite() && energy in 0f..1f)
                assertTrue("${definition.catalogId} label", label.isFinite() && label in 0f..1f)
                assertTrue(camera.translationX.isFinite())
                assertTrue(camera.translationY.isFinite())
                assertTrue(camera.scale.isFinite() && camera.scale >= 1f)
                elapsed += 16
            }
            assertEquals(0f, skillLabelAlpha(SKILL_PRESENTATION_DURATION_MILLIS, definition))
            assertEquals(false, skillDamageFrame(SKILL_PRESENTATION_DURATION_MILLIS, definition, 10L).visible)
            assertEquals(SkillCameraFrame(), skillCameraFrame(SKILL_PRESENTATION_DURATION_MILLIS, definition))
            assertEquals(0f, skillEnergyFraction(SKILL_PRESENTATION_DURATION_MILLIS, 1f, 0f, definition), 0f)
        }
    }

    @Test
    fun `long combos reserve camera impulses for readable accent hits`() {
        SkillCatalog.all.filter { it.hitCount >= 2 }.forEach { definition ->
            skillPresentationHits(definition).forEach { presentationHit ->
                val timing = presentationHit.timingMillis
                val frame = skillCameraFrame(timing + 16, definition)
                assertTrue(
                    "${definition.catalogId} accent hit at $timing was hidden",
                    frame.scale > 1f || frame.translationX != 0f || frame.translationY != 0f,
                )
            }
        }
    }

    @Test
    fun `catalog uses every visual axis and semantic keyword overrides`() {
        assertEquals(SkillMotion.entries.toSet(), SkillCatalog.all.map { it.motion }.toSet())
        assertEquals(SkillElement.entries.toSet(), SkillCatalog.all.map { it.element }.toSet())
        assertEquals(SkillTimingProfile.entries.toSet(), SkillCatalog.all.map { it.timingProfile }.toSet())
        assertEquals(SkillFinisher.entries.toSet(), SkillCatalog.all.map { it.finisher }.toSet())

        SkillCatalog.all.filter { it.name.contains("천둥") || it.name.contains("번개") }.forEach {
            assertEquals("${it.catalogId} ${it.name}", SkillElement.LIGHTNING, it.element)
        }
        SkillCatalog.all.filter { it.name.contains("독가시") }.forEach {
            assertEquals("${it.catalogId} ${it.name}", SkillElement.POISON, it.element)
        }
        SkillCatalog.all.filter { it.name.contains("낙하") }.forEach {
            assertEquals("${it.catalogId} ${it.name}", SkillMotion.PILLAR_DROP, it.motion)
        }

        val perceivedSignatures = SkillCatalog.all.map {
            listOf(
                it.heroClass,
                it.element,
                it.motion,
                it.finisher,
                it.hitCount,
                it.timingProfile,
                it.effectVariant,
            )
        }.toSet()
        assertTrue("perceived visual signatures=${perceivedSignatures.size}", perceivedSignatures.size >= 420)
    }


    @Test
    fun `all six hundred skills resolve to the modular renderer archetypes`() {
        assertEquals(600, SkillCatalog.all.size)
        val motionArchetypes = SkillCatalog.all.map {
            modularVfxArchetype(it.motion)
        }.toSet()
        assertEquals(
            setOf(ModularVfxArchetype.SLASH, ModularVfxArchetype.PROJECTILE, ModularVfxArchetype.COLUMN, ModularVfxArchetype.VORTEX),
            motionArchetypes,
        )
        assertEquals(SkillMotion.entries.toSet(), SkillCatalog.all.groupBy {
            modularVfxArchetype(it.motion)
        }.values.flatten().map { it.motion }.toSet())

        SkillCatalog.all.forEach { definition ->
            definition.hitTimingsMillis.indices.forEach { hitIndex ->
                val point = modularHitPoint(definition, hitIndex)
                assertTrue("${definition.catalogId} x=${point.xFraction}", point.xFraction in 0.30f..0.70f)
                assertTrue("${definition.catalogId} y=${point.yFraction}", point.yFraction in 0.50f..0.68f)
                assertTrue(
                    "${definition.catalogId} layer count",
                    modularLayerCount(definition, hitIndex == definition.hitTimingsMillis.lastIndex) in 1..8,
                )
            }
        }
    }

    @Test
    fun `every motion family gets a distinct modular asset behavior`() {
        assertEquals(ModularVfxArchetype.SLASH, modularVfxArchetype(SkillMotion.CROSS_SLASH))
        assertEquals(ModularVfxArchetype.PROJECTILE, modularVfxArchetype(SkillMotion.PROJECTILE_VOLLEY))
        assertEquals(ModularVfxArchetype.COLUMN, modularVfxArchetype(SkillMotion.RAIN_VERTICAL))
        assertEquals(ModularVfxArchetype.VORTEX, modularVfxArchetype(SkillMotion.GRAVITY_COLLAPSE))
    }

    @Test
    fun `the six hundred skill catalog uses semantic name matching across the forty neutral resources`() {
        val usage = SkillCatalog.all.groupBy { modularRecipe(it).archetype }

        listOf(
            ModularVfxArchetype.WARRIOR,
            ModularVfxArchetype.SLASH,
            ModularVfxArchetype.PROJECTILE,
            ModularVfxArchetype.COLUMN,
            ModularVfxArchetype.VORTEX,
        ).forEach { family ->
            val minimumResources = when (family) {
                ModularVfxArchetype.WARRIOR -> 8
                ModularVfxArchetype.SLASH -> 8
                ModularVfxArchetype.PROJECTILE -> 8
                ModularVfxArchetype.COLUMN -> 8
                ModularVfxArchetype.VORTEX -> 7
                ModularVfxArchetype.IMPACT -> 0
            }
            assertTrue(
                "$family should not collapse back to one image",
                usage.getValue(family).map { modularRecipe(it).index }.toSet().size >= minimumResources,
            )
        }
        SkillCatalog.all.forEach { definition ->
            val plan = semanticVfxPlan(definition)
            assertTrue("${definition.catalogId} has no semantic reason", plan.reason.isNotBlank())
            assertEquals(plan.recipe, modularRecipe(definition))
        }
    }

    @Test
    fun `representative skill names select a matching action direction and resource`() {
        fun plan(name: String) = semanticVfxPlan(SkillCatalog.all.single { it.name == name })

        assertEquals(SemanticVfxAction.CUT, plan("칼날 베기").action)
        assertEquals(ModularVfxArchetype.WARRIOR, plan("칼날 베기").recipe.archetype)
        assertEquals(SemanticVfxAction.DESCEND, plan("완력 내려찍기").action)
        assertEquals(SemanticVfxFlow.TOP_TO_BOTTOM, plan("완력 내려찍기").flow)
        assertEquals(SemanticVfxAction.CHARGE, plan("전열 돌진").action)
        assertEquals(SemanticVfxFlow.LEFT_TO_RIGHT, plan("전열 돌진").flow)
        assertEquals(SemanticVfxAction.ASCEND, plan("용암 분출").action)
        assertEquals(SemanticVfxFlow.BOTTOM_TO_TOP, plan("용암 분출").flow)
        assertEquals(SemanticVfxAction.CHAIN, plan("연쇄 번개").action)
        assertEquals(SemanticVfxAction.COLLAPSE, plan("무한 특이점 붕괴").action)
        assertEquals(SemanticVfxFlow.OUTSIDE_IN, plan("무한 특이점 붕괴").flow)
        assertEquals(SemanticVfxAction.TRAP, plan("칼날 감옥").action)
    }

    @Test
    fun `steel slash synchronizes one restrained heavy cut at 420ms`() {
        val definition = SkillCatalog.find("warrior_t01_c01")!!

        assertEquals(1, definition.hitCount)
        assertEquals(listOf(420), definition.hitTimingsMillis)
        assertEquals(false, steelSlashVfxFrame(339, reducedMotion = false).visible)
        assertTrue(steelSlashVfxFrame(404, reducedMotion = false).reveal < 1f)
        assertEquals(1f, steelSlashVfxFrame(420, reducedMotion = false).reveal)
        assertEquals(0.80f, steelSlashVfxFrame(420, reducedMotion = false).alpha, 0.0001f)
        assertTrue(skillDamageFrame(420, definition, 432L).visible)
        assertEquals(0.70f, skillDamageFrame(420, definition, 432L).alpha, 0.0001f)
        assertTrue(skillCameraFrame(420, definition).translationX < 0f)
        assertEquals(0f, skillEnergyFraction(516, 1f, 0f, definition), 0.0001f)
        assertEquals(false, steelSlashVfxFrame(720, reducedMotion = false).visible)
        assertEquals(false, skillDamageFrame(820, definition, 432L).visible)
        assertEquals(0f, skillLabelAlpha(820, definition), 0.0001f)
        assertEquals(SkillCameraFrame(), skillCameraFrame(820, definition))
    }

    @Test
    fun `steel slash reduced motion keeps static feedback without camera movement`() {
        val definition = SkillCatalog.find("warrior_t01_c01")!!
        val frame = steelSlashVfxFrame(420, reducedMotion = true)

        assertTrue(frame.visible)
        assertEquals(1f, frame.reveal)
        assertEquals(0.48f, frame.alpha, 0.0001f)
        assertEquals(1f, skillEnergyFraction(419, 1f, 0f, definition, reducedMotion = true), 0f)
        assertEquals(0f, skillEnergyFraction(420, 1f, 0f, definition, reducedMotion = true), 0f)
        assertEquals(false, steelSlashVfxFrame(580, reducedMotion = true).visible)
    }

    @Test
    fun `explicit action words always outrank catalog fallback motions`() {
        SkillCatalog.all.forEach { definition ->
            val name = definition.name
            val action = semanticVfxPlan(definition).action
            when {
                name.contains("내려치기") || name.contains("낙하") || name.contains("강하") ->
                    assertEquals("${definition.catalogId} $name", SemanticVfxAction.DESCEND, action)
                name.contains("분출") || name.contains("솟구침") ->
                    assertEquals("${definition.catalogId} $name", SemanticVfxAction.ASCEND, action)
                name.contains("연쇄") || name.contains("사슬") ->
                    assertTrue(
                        "${definition.catalogId} $name -> $action",
                        action in setOf(SemanticVfxAction.CHAIN, SemanticVfxAction.TRAP),
                    )
                name.contains("소용돌이") || name.contains("회오리") ->
                    assertEquals("${definition.catalogId} $name", SemanticVfxAction.SPIN, action)
                name.contains("붕괴") || name.contains("특이점") ->
                    assertEquals("${definition.catalogId} $name", SemanticVfxAction.COLLAPSE, action)
                name.contains("찌르기") || name.contains("관통") ->
                    assertTrue(
                        "${definition.catalogId} $name -> $action",
                        action in setOf(SemanticVfxAction.PIERCE, SemanticVfxAction.FLURRY, SemanticVfxAction.EXECUTE, SemanticVfxAction.CHARGE),
                    )
                name.contains("베기") || name.contains("참격") || name.contains("가르기") ->
                    assertTrue(
                        "${definition.catalogId} $name -> $action",
                        action in setOf(
                            SemanticVfxAction.CUT,
                            SemanticVfxAction.CROSS_CUT,
                            SemanticVfxAction.FLURRY,
                            SemanticVfxAction.SPIN,
                            SemanticVfxAction.EXECUTE,
                        ),
                    )
            }
        }
    }

    @Test
    fun `starter warrior combo uses three readable alternating cuts`() {
        val definition = SkillCatalog.find("warrior_t01_c05")!!
        val frames = definition.hitTimingsMillis.indices.map { semanticHitChoreography(definition, it) }

        assertEquals(3, definition.hitCount)
        assertEquals(SkillElement.PHYSICAL, definition.element)
        assertTrue(isContinuousSlash(definition))
        assertEquals(ModularVfxArchetype.WARRIOR, frames.first().recipe.archetype)
        assertTrue(frames.map { it.recipe }.distinct().size >= 2)
        assertTrue(frames.dropLast(1).map { it.xOffsetFraction }.distinct().size >= 2)
        assertTrue(frames.dropLast(1).map { it.yOffsetFraction }.distinct().size >= 2)
        assertEquals(listOf(1f, -1f, 1f), frames.map { it.visualDirection })
        assertTrue(frames[0].xOffsetFraction != frames[2].xOffsetFraction)
        assertEquals(0f, frames.last().xOffsetFraction)
        assertTrue(frames.last().widthScale > frames.first().widthScale)

        val first = continuousSlashVfxFrame(140, 0, reducedMotion = false)
        val second = continuousSlashVfxFrame(300, 1, reducedMotion = false)
        val final = continuousSlashVfxFrame(520, 2, reducedMotion = false)
        assertTrue(first.visible)
        assertTrue(second.visible)
        assertTrue(final.visible)
        assertEquals(listOf(1f, -1f, 1f), listOf(first.direction, second.direction, final.direction))
        assertTrue(first.rotationDegrees != second.rotationDegrees)
        assertTrue(second.rotationDegrees != final.rotationDegrees)
        assertTrue(first.xFraction != second.xFraction)
        assertTrue(final.scale > first.scale)
        assertEquals(false, continuousSlashVfxFrame(800, 2, reducedMotion = false).visible)
    }

    @Test
    fun `half moon slash keeps one broad crescent instead of a cross cut`() {
        val definition = SkillCatalog.find("warrior_t02_c01")!!
        assertTrue(isHalfMoonSlash(definition))
        assertEquals(1, definition.hitCount)
        assertEquals(SemanticVfxAction.CUT, semanticVfxPlan(definition).action)
        assertEquals(SemanticVfxFlow.LEFT_TO_RIGHT, semanticVfxPlan(definition).flow)
    }

    @Test
    fun `level ten warrior block keeps five distinct readable actions`() {
        val armor = SkillCatalog.find("warrior_t02_c02")!!
        val frontline = SkillCatalog.find("warrior_t02_c03")!!
        val stoneDust = SkillCatalog.find("warrior_t02_c04")!!
        val triple = SkillCatalog.find("warrior_t02_c05")!!

        assertTrue(isArmorShatter(armor))
        assertEquals(SemanticVfxAction.DESCEND, semanticVfxPlan(armor).action)
        assertTrue(isFrontlineBreakthrough(frontline))
        assertEquals(SemanticVfxAction.CHARGE, semanticVfxPlan(frontline).action)
        assertTrue(isStoneDustBurst(stoneDust))
        assertEquals(SemanticVfxAction.BURST, semanticVfxPlan(stoneDust).action)
        assertTrue(isTripleSlash(triple))
        assertEquals(3, triple.hitCount)
        assertEquals(SkillElement.PHYSICAL, triple.element)
        assertEquals(SemanticVfxAction.FLURRY, semanticVfxPlan(triple).action)

        val first = starterWarriorComboFrame(180, 0, triple.hitTimingsMillis, reducedMotion = false, stronger = true)
        val second = starterWarriorComboFrame(430, 1, triple.hitTimingsMillis, reducedMotion = false, stronger = true)
        val final = starterWarriorComboFrame(720, 2, triple.hitTimingsMillis, reducedMotion = false, stronger = true)
        assertTrue(first.visible)
        assertTrue(second.visible)
        assertTrue(final.visible)
        assertEquals(listOf(1f, -1f, 1f), listOf(first.direction, second.direction, final.direction))
        assertTrue(first.rotationDegrees != second.rotationDegrees)
        assertTrue(second.rotationDegrees != final.rotationDegrees)
        assertTrue(final.scale > first.scale)
    }

    @Test
    fun `level fifteen warrior block keeps attack meaning and physical beast frenzy`() {
        val battlefield = SkillCatalog.find("warrior_t03_c01")!!
        val ironWall = SkillCatalog.find("warrior_t03_c02")!!
        val advance = SkillCatalog.find("warrior_t03_c03")!!
        val fissure = SkillCatalog.find("warrior_t03_c04")!!
        val beast = SkillCatalog.find("warrior_t03_c05")!!

        assertTrue(isBattlefieldCleave(battlefield))
        assertEquals(SemanticVfxAction.CUT, semanticVfxPlan(battlefield).action)
        assertTrue(isIronWallSmash(ironWall))
        assertEquals(SemanticVfxAction.DESCEND, semanticVfxPlan(ironWall).action)
        assertTrue(isFuriousAdvance(advance))
        assertEquals(SemanticVfxAction.CHARGE, semanticVfxPlan(advance).action)
        assertTrue(isEarthFissure(fissure))
        assertEquals(SemanticVfxAction.BURST, semanticVfxPlan(fissure).action)
        assertTrue(isBeastFrenzy(beast))
        assertEquals(5, beast.hitCount)
        assertEquals(SkillElement.PHYSICAL, beast.element)
        assertEquals(SemanticVfxAction.FLURRY, semanticVfxPlan(beast).action)
    }

    @Test
    fun `level twenty warrior block keeps wind cut rise and five physical hits`() {
        val bloodWind = SkillCatalog.find("warrior_t04_c01")!!
        val helmet = SkillCatalog.find("warrior_t04_c02")!!
        val castle = SkillCatalog.find("warrior_t04_c03")!!
        val rock = SkillCatalog.find("warrior_t04_c04")!!
        val five = SkillCatalog.find("warrior_t04_c05")!!

        assertTrue(isBloodWindSlash(bloodWind))
        assertEquals(SemanticVfxAction.CUT, semanticVfxPlan(bloodWind).action)
        assertTrue(isHelmetCrusher(helmet))
        assertEquals(SemanticVfxAction.DESCEND, semanticVfxPlan(helmet).action)
        assertTrue(isCastleBreakerCharge(castle))
        assertEquals(SemanticVfxAction.CHARGE, semanticVfxPlan(castle).action)
        assertTrue(isRockEruption(rock))
        assertEquals(SemanticVfxAction.ASCEND, semanticVfxPlan(rock).action)
        assertTrue(isFiveStrike(five))
        assertEquals(5, five.hitCount)
        assertEquals(SkillElement.PHYSICAL, five.element)
        assertEquals(SemanticVfxAction.FLURRY, semanticVfxPlan(five).action)
    }

    @Test
    fun `level twenty five warrior block adds larger cut charge quake and physical chain`() {
        val swordLight = SkillCatalog.find("warrior_t05_c01")!!
        val axe = SkillCatalog.find("warrior_t05_c02")!!
        val wedge = SkillCatalog.find("warrior_t05_c03")!!
        val quake = SkillCatalog.find("warrior_t05_c04")!!
        val berserk = SkillCatalog.find("warrior_t05_c05")!!

        assertTrue(isSwordLightSever(swordLight))
        assertEquals(SemanticVfxAction.CUT, semanticVfxPlan(swordLight).action)
        assertTrue(isBattleAxeDescent(axe))
        assertEquals(SemanticVfxAction.DESCEND, semanticVfxPlan(axe).action)
        assertTrue(isWedgeBreakthrough(wedge))
        assertEquals(SemanticVfxAction.CHARGE, semanticVfxPlan(wedge).action)
        assertTrue(isSeismicWave(quake))
        assertEquals(SemanticVfxAction.BURST, semanticVfxPlan(quake).action)
        assertTrue(isBerserkerChainSlash(berserk))
        assertEquals(5, berserk.hitCount)
        assertEquals(SkillElement.PHYSICAL, berserk.element)
    }

    @Test
    fun `level thirty warrior block separates rotation hammer charge fracture and physical frenzy`() {
        val spin = SkillCatalog.find("warrior_t06_c01")!!
        val hammer = SkillCatalog.find("warrior_t06_c02")!!
        val charge = SkillCatalog.find("warrior_t06_c03")!!
        val fault = SkillCatalog.find("warrior_t06_c04")!!
        val storm = SkillCatalog.find("warrior_t06_c05")!!
        assertTrue(isRotatingSlash(spin)); assertEquals(SemanticVfxAction.SPIN, semanticVfxPlan(spin).action)
        assertTrue(isGiantHammer(hammer)); assertEquals(SemanticVfxAction.DESCEND, semanticVfxPlan(hammer).action)
        assertTrue(isIroncladCharge(charge)); assertEquals(SemanticVfxAction.CHARGE, semanticVfxPlan(charge).action)
        assertTrue(isFaultShatter(fault)); assertEquals(SemanticVfxAction.BURST, semanticVfxPlan(fault).action)
        assertTrue(isStormFrenzy(storm)); assertEquals(6, storm.hitCount); assertEquals(SkillElement.PHYSICAL, storm.element)
    }

    @Test
    fun `level thirty five warrior block keeps every weapon attack physical or earth`() {
        val a=SkillCatalog.find("warrior_t07_c01")!!;val b=SkillCatalog.find("warrior_t07_c02")!!;val c=SkillCatalog.find("warrior_t07_c03")!!;val d=SkillCatalog.find("warrior_t07_c04")!!;val e=SkillCatalog.find("warrior_t07_c05")!!
        assertTrue(isLionSlash(a));assertEquals(SemanticVfxAction.CUT,semanticVfxPlan(a).action)
        assertTrue(isBoneCrushingBlow(b));assertEquals(SemanticVfxAction.DESCEND,semanticVfxPlan(b).action)
        assertTrue(isLightningBreakthrough(c));assertEquals(SkillElement.PHYSICAL,c.element)
        assertTrue(isMountainFist(d));assertEquals(SemanticVfxAction.ASCEND,semanticVfxPlan(d).action)
        assertTrue(isCrimsonCombo(e));assertEquals(4,e.hitCount);assertEquals(SkillElement.PHYSICAL,e.element)
    }

    @Test
    fun `level forty warrior block keeps gate charge roar and exact seven physical cuts`() {
        val a=SkillCatalog.find("warrior_t08_c01")!!;val b=SkillCatalog.find("warrior_t08_c02")!!;val c=SkillCatalog.find("warrior_t08_c03")!!;val d=SkillCatalog.find("warrior_t08_c04")!!;val e=SkillCatalog.find("warrior_t08_c05")!!
        assertTrue(isValiantCleave(a));assertEquals(SemanticVfxAction.CUT,semanticVfxPlan(a).action)
        assertTrue(isGateDestroyer(b));assertEquals(SemanticVfxAction.DESCEND,semanticVfxPlan(b).action)
        assertTrue(isChariotCharge(c));assertEquals(SemanticVfxAction.CHARGE,semanticVfxPlan(c).action)
        assertTrue(isEarthRoar(d));assertEquals(SemanticVfxAction.BURST,semanticVfxPlan(d).action)
        assertTrue(isSevenSlash(e));assertEquals(7,e.hitCount);assertEquals(SkillElement.PHYSICAL,e.element)
    }

    @Test
    fun `level forty five warrior block uses steel spin shattered armor rift and physical frenzy`() {
        val a=SkillCatalog.find("warrior_t09_c01")!!;val b=SkillCatalog.find("warrior_t09_c02")!!;val c=SkillCatalog.find("warrior_t09_c03")!!;val d=SkillCatalog.find("warrior_t09_c04")!!;val e=SkillCatalog.find("warrior_t09_c05")!!
        assertTrue(isSteelWhirlwind(a));assertEquals(SemanticVfxAction.SPIN,semanticVfxPlan(a).action)
        assertTrue(isSmashToPieces(b));assertEquals(SemanticVfxAction.DESCEND,semanticVfxPlan(b).action)
        assertTrue(isIndomitableAdvance(c));assertEquals(SemanticVfxAction.CHARGE,semanticVfxPlan(c).action)
        assertTrue(isRiftExplosion(d));assertEquals(SemanticVfxAction.BURST,semanticVfxPlan(d).action)
        assertTrue(isFrenzyBlades(e));assertEquals(6,e.hitCount);assertEquals(SkillElement.PHYSICAL,e.element)
    }

    @Test
    fun `level fifty warrior block keeps siege smash charge ground and frenzy roles`() {
        val a=SkillCatalog.find("warrior_t10_c01")!!;val b=SkillCatalog.find("warrior_t10_c02")!!;val c=SkillCatalog.find("warrior_t10_c03")!!;val d=SkillCatalog.find("warrior_t10_c04")!!;val e=SkillCatalog.find("warrior_t10_c05")!!
        assertTrue(isKingdomSever(a));assertEquals(SemanticVfxAction.CUT,semanticVfxPlan(a).action)
        assertTrue(isThunderDownstrike(b));assertEquals(SkillElement.EARTH,b.element)
        assertTrue(isVanguardBreakthrough(c));assertEquals(SemanticVfxAction.CHARGE,semanticVfxPlan(c).action)
        assertTrue(isLeylineEruption(d));assertEquals(SemanticVfxAction.ASCEND,semanticVfxPlan(d).action)
        assertTrue(isBloodFrenzy(e));assertEquals(7,e.hitCount);assertEquals(SkillElement.PHYSICAL,e.element)
    }

    @Test
    fun `level fifty five warrior block keeps slash smash charge ground and physical frenzy`() {
        val a=SkillCatalog.find("warrior_t11_c01")!!;val b=SkillCatalog.find("warrior_t11_c02")!!;val c=SkillCatalog.find("warrior_t11_c03")!!;val d=SkillCatalog.find("warrior_t11_c04")!!;val e=SkillCatalog.find("warrior_t11_c05")!!
        assertTrue(isSwordEmperorHalfMoon(a));assertEquals(SemanticVfxAction.CUT,semanticVfxPlan(a).action)
        assertTrue(isCastleCrushingGreatSmash(b));assertEquals(SemanticVfxAction.DESCEND,semanticVfxPlan(b).action)
        assertTrue(isUnbeatenCharge(c));assertEquals(SemanticVfxAction.CHARGE,semanticVfxPlan(c).action)
        assertTrue(isContinentalFissure(d));assertEquals(SemanticVfxAction.BURST,semanticVfxPlan(d).action)
        assertTrue(isHotWindChainSlash(e));assertEquals(7,e.hitCount);assertEquals(SkillElement.PHYSICAL,e.element)
    }

    @Test
    fun `level sixty warrior block keeps martial slash smash charge collapse and frenzy`() {
        val a=SkillCatalog.find("warrior_t12_c01")!!;val b=SkillCatalog.find("warrior_t12_c02")!!;val c=SkillCatalog.find("warrior_t12_c03")!!;val d=SkillCatalog.find("warrior_t12_c04")!!;val e=SkillCatalog.find("warrior_t12_c05")!!
        assertTrue(isTitanCleave(a));assertEquals(SemanticVfxAction.CUT,semanticVfxPlan(a).action)
        assertTrue(isCometSmash(b));assertEquals(SkillElement.EARTH,b.element)
        assertTrue(isKingsAdvance(c));assertEquals(SemanticVfxAction.CHARGE,semanticVfxPlan(c).action)
        assertTrue(isMountainCollapse(d));assertEquals(SemanticVfxAction.COLLAPSE,semanticVfxPlan(d).action)
        assertTrue(isHundredBattleFrenzy(e));assertEquals(7,e.hitCount);assertEquals(SkillElement.PHYSICAL,e.element)
    }

    @Test
    fun `level sixty five warrior block escalates war god dragon bone iron blood earth and tyrant`() {
        val a=SkillCatalog.find("warrior_t13_c01")!!;val b=SkillCatalog.find("warrior_t13_c02")!!;val c=SkillCatalog.find("warrior_t13_c03")!!;val d=SkillCatalog.find("warrior_t13_c04")!!;val e=SkillCatalog.find("warrior_t13_c05")!!
        assertTrue(isWarGodBlade(a));assertEquals(SemanticVfxAction.CUT,semanticVfxPlan(a).action)
        assertTrue(isDragonBoneShatter(b));assertEquals(SemanticVfxAction.DESCEND,semanticVfxPlan(b).action)
        assertTrue(isIronBloodBreakthrough(c));assertEquals(SemanticVfxAction.CHARGE,semanticVfxPlan(c).action)
        assertTrue(isEarthRage(d));assertEquals(SemanticVfxAction.BURST,semanticVfxPlan(d).action)
        assertTrue(isTyrantCombo(e));assertEquals(6,e.hitCount);assertEquals(SkillElement.PHYSICAL,e.element)
    }

    @Test
    fun `level seventy warrior block escalates sky cliff legion crust and infinite slash`() {
        val a=SkillCatalog.find("warrior_t14_c01")!!;val b=SkillCatalog.find("warrior_t14_c02")!!;val c=SkillCatalog.find("warrior_t14_c03")!!;val d=SkillCatalog.find("warrior_t14_c04")!!;val e=SkillCatalog.find("warrior_t14_c05")!!
        assertTrue(isSkySever(a));assertEquals(SemanticVfxAction.CUT,semanticVfxPlan(a).action)
        assertTrue(isCliffDescent(b));assertEquals(SemanticVfxAction.DESCEND,semanticVfxPlan(b).action)
        assertTrue(isLegionCharge(c));assertEquals(SemanticVfxAction.CHARGE,semanticVfxPlan(c).action)
        assertTrue(isCrustExplosion(d));assertEquals(SemanticVfxAction.BURST,semanticVfxPlan(d).action)
        assertTrue(isInfiniteSlash(e));assertEquals(10,e.hitCount);assertEquals(SkillElement.PHYSICAL,e.element)
    }

    @Test
    fun `level seventy five warrior block keeps royal weapon and ground identities`() {
        val a=SkillCatalog.find("warrior_t15_c01")!!;val b=SkillCatalog.find("warrior_t15_c02")!!;val c=SkillCatalog.find("warrior_t15_c03")!!;val d=SkillCatalog.find("warrior_t15_c04")!!;val e=SkillCatalog.find("warrior_t15_c05")!!
        assertTrue(isGoldenLionSlash(a));assertEquals(SemanticVfxAction.CUT,semanticVfxPlan(a).action)
        assertTrue(isStarIronShatter(b));assertEquals(SkillElement.EARTH,b.element)
        assertTrue(isEmperorCharge(c));assertEquals(SemanticVfxAction.CHARGE,semanticVfxPlan(c).action)
        assertTrue(isWorldFissure(d));assertEquals(SemanticVfxAction.COLLAPSE,semanticVfxPlan(d).action)
        assertTrue(isMeteorFrenzy(e));assertEquals(8,e.hitCount);assertEquals(SkillElement.PHYSICAL,e.element)
    }

    @Test
    fun `level eighty warrior block keeps physical slash charge frenzy and earth impact`() {
        val a=SkillCatalog.find("warrior_t16_c01")!!;val b=SkillCatalog.find("warrior_t16_c02")!!;val c=SkillCatalog.find("warrior_t16_c03")!!;val d=SkillCatalog.find("warrior_t16_c04")!!;val e=SkillCatalog.find("warrior_t16_c05")!!
        assertTrue(isStormKingSlash(a));assertEquals(SkillElement.PHYSICAL,a.element)
        assertTrue(isJudgmentSmash(b));assertEquals(SemanticVfxAction.DESCEND,semanticVfxPlan(b).action)
        assertTrue(isCitadelPierce(c));assertEquals(SemanticVfxAction.CHARGE,semanticVfxPlan(c).action)
        assertTrue(isEarthDoom(d));assertEquals(SemanticVfxAction.COLLAPSE,semanticVfxPlan(d).action)
        assertTrue(isTwelveSlash(e));assertEquals(12,e.hitCount)
    }

    @Test
    fun `level eighty five warrior block escalates dragon slayer mount tai myth heaven earth and berserker god`() {
        val a=SkillCatalog.find("warrior_t17_c01")!!;val b=SkillCatalog.find("warrior_t17_c02")!!;val c=SkillCatalog.find("warrior_t17_c03")!!;val d=SkillCatalog.find("warrior_t17_c04")!!;val e=SkillCatalog.find("warrior_t17_c05")!!
        assertTrue(isDragonSlayerCleave(a));assertEquals(SemanticVfxAction.CUT,semanticVfxPlan(a).action)
        assertTrue(isMountTaiCollapse(b));assertEquals(SemanticVfxAction.COLLAPSE,semanticVfxPlan(b).action)
        assertTrue(isMythBreakthrough(c));assertEquals(SemanticVfxAction.CHARGE,semanticVfxPlan(c).action)
        assertTrue(isHeavenEarthShatter(d));assertEquals(SemanticVfxAction.BURST,semanticVfxPlan(d).action)
        assertTrue(isBerserkerGodFrenzy(e));assertEquals(9,e.hitCount);assertEquals(SkillElement.PHYSICAL,e.element)
    }

    @Test
    fun `level ninety warrior block keeps war king weapon and terrain identities`() {
        val a=SkillCatalog.find("warrior_t18_c01")!!;val b=SkillCatalog.find("warrior_t18_c02")!!;val c=SkillCatalog.find("warrior_t18_c03")!!;val d=SkillCatalog.find("warrior_t18_c04")!!;val e=SkillCatalog.find("warrior_t18_c05")!!
        assertTrue(isOverlordSever(a));assertEquals(SemanticVfxAction.CUT,semanticVfxPlan(a).action)
        assertTrue(isDoomHammer(b));assertEquals(SemanticVfxAction.DESCEND,semanticVfxPlan(b).action)
        assertTrue(isWarKingAdvance(c));assertEquals(SemanticVfxAction.CHARGE,semanticVfxPlan(c).action)
        assertTrue(isContinentCollapse(d));assertEquals(SemanticVfxAction.COLLAPSE,semanticVfxPlan(d).action)
        assertTrue(isBloodWindCombo(e));assertEquals(8,e.hitCount);assertEquals(SkillElement.PHYSICAL,e.element)
    }

    @Test
    fun `level ninety five warrior block peaks with martial and siege identities`() {
        val a=SkillCatalog.find("warrior_t19_c01")!!;val b=SkillCatalog.find("warrior_t19_c02")!!;val c=SkillCatalog.find("warrior_t19_c03")!!;val d=SkillCatalog.find("warrior_t19_c04")!!;val e=SkillCatalog.find("warrior_t19_c05")!!
        assertTrue(isWorldSplit(a));assertEquals(SemanticVfxAction.CUT,semanticVfxPlan(a).action)
        assertTrue(isStarBreakingStrike(b));assertEquals(SemanticVfxAction.DESCEND,semanticVfxPlan(b).action);assertEquals(SkillElement.EARTH,b.element)
        assertTrue(isInvincibleGrandCharge(c));assertEquals(SemanticVfxAction.CHARGE,semanticVfxPlan(c).action)
        assertTrue(isWorldAxisCollapse(d));assertEquals(SemanticVfxAction.COLLAPSE,semanticVfxPlan(d).action)
        assertTrue(isHundredLotusSwordDance(e));assertEquals(10,e.hitCount);assertEquals(SkillElement.PHYSICAL,e.element)
    }

    @Test
    fun `level one hundred warrior block peaks with end divine vanguard creation and infinite frenzy`() {
        val a=SkillCatalog.find("warrior_t20_c01")!!;val b=SkillCatalog.find("warrior_t20_c02")!!;val c=SkillCatalog.find("warrior_t20_c03")!!;val d=SkillCatalog.find("warrior_t20_c04")!!;val e=SkillCatalog.find("warrior_t20_c05")!!
        assertTrue(isEndSword(a));assertEquals(SemanticVfxAction.CUT,semanticVfxPlan(a).action)
        assertTrue(isDivineShatter(b));assertEquals(SemanticVfxAction.DESCEND,semanticVfxPlan(b).action)
        assertTrue(isLastVanguard(c));assertEquals(SemanticVfxAction.CHARGE,semanticVfxPlan(c).action)
        assertTrue(isCreationEarthquake(d));assertEquals(SemanticVfxAction.BURST,semanticVfxPlan(d).action)
        assertTrue(isInfiniteFrenzy(e));assertEquals(12,e.hitCount);assertEquals(SkillElement.PHYSICAL,e.element)
    }

    @Test
    fun `creation earthquake promotes the approved web sprite and keeps only contact impact`() {
        val definition = checkNotNull(SkillCatalog.find("warrior_t20_c04"))
        val viewportWidth = 361f
        val viewportHeight = 160f
        val expectedFrames = listOf(
            80 to R.drawable.vfx11_warrior_genesis_01,
            125 to R.drawable.vfx11_warrior_genesis_02,
            170 to R.drawable.vfx11_warrior_genesis_03,
            215 to R.drawable.vfx11_warrior_genesis_04,
            255 to R.drawable.vfx11_warrior_genesis_05,
            295 to R.drawable.vfx11_warrior_genesis_06,
            330 to R.drawable.vfx11_warrior_genesis_07,
            360 to R.drawable.vfx11_warrior_genesis_08,
            390 to R.drawable.vfx11_warrior_genesis_09,
            420 to R.drawable.vfx11_warrior_genesis_10,
            500 to R.drawable.vfx11_warrior_genesis_11,
        )

        assertEquals(expectedFrames.map { it.second }, creationEarthquakeSpriteAssetIds.toList())
        listOf(320f, 361f, 720f).forEach { candidateWidth ->
            val squareFrame = authoredRenderableFramePlan(
                definition,
                650,
                false,
                candidateWidth,
                viewportHeight,
            ).single { it.role == AuthoredLayerRole.PRIMARY }
            assertEquals(
                "sprite is not square at ${candidateWidth}x$viewportHeight",
                squareFrame.widthFraction * candidateWidth,
                squareFrame.heightFraction * viewportHeight,
                .001f,
            )
        }
        val lastHitMillis = definition.hitTimingsMillis.last()
        assertEquals(0f, creationEarthquakeGlowAlpha(0, lastHitMillis, false), .0001f)
        assertEquals(.58f, creationEarthquakeGlowAlpha(110, lastHitMillis, false), .0001f)
        assertTrue(creationEarthquakeGlowAlpha(650, lastHitMillis, false) in 0f..58f)
        assertEquals(0f, creationEarthquakeGlowAlpha(SKILL_VFX_END_MILLIS, lastHitMillis, false), .0001f)
        listOf(-1, 0, 110, 650, SKILL_VFX_END_MILLIS).forEach { elapsed ->
            assertEquals(.42f, creationEarthquakeGlowAlpha(elapsed, lastHitMillis, true), .0001f)
        }
        expectedFrames.forEach { (elapsed, assetId) ->
            val primary = authoredRenderableFramePlan(
                definition,
                elapsed,
                false,
                viewportWidth,
                viewportHeight,
            ).single { it.role == AuthoredLayerRole.PRIMARY }
            assertEquals("wrong sprite at ${elapsed}ms", assetId, primary.assetId)
            assertEquals(.50f, primary.xFraction, .0001f)
            assertEquals(.72f, primary.yFraction, .0001f)
        }

        val impactFrames = authoredRenderableFramePlan(
            definition,
            420,
            false,
            viewportWidth,
            viewportHeight,
        )
        assertEquals(
            setOf(AuthoredLayerRole.PRIMARY, AuthoredLayerRole.CONTACT),
            impactFrames.map { it.role }.toSet(),
        )
        val aftermath = authoredRenderableFramePlan(
            definition,
            650,
            false,
            viewportWidth,
            viewportHeight,
        )
        assertEquals(listOf(AuthoredLayerRole.PRIMARY), aftermath.map { it.role })
        assertEquals(R.drawable.vfx11_warrior_genesis_11, aftermath.single().assetId)
        val fading = authoredRenderableFramePlan(
            definition,
            880,
            false,
            viewportWidth,
            viewportHeight,
        ).single { it.role == AuthoredLayerRole.PRIMARY }
        assertEquals(.5f, fading.alpha, .0001f)
        assertTrue(
            authoredRenderableFramePlan(definition, 980, false, viewportWidth, viewportHeight)
                .none { it.role == AuthoredLayerRole.PRIMARY },
        )
        val reducedSamples = (0 until SKILL_VFX_END_MILLIS step 10).flatMap { elapsed ->
            authoredRenderableFramePlan(
                definition,
                elapsed,
                true,
                viewportWidth,
                viewportHeight,
            ).filter { it.role == AuthoredLayerRole.PRIMARY }
        }
        assertEquals(setOf(R.drawable.vfx11_warrior_genesis_11), reducedSamples.map { it.assetId }.toSet())
        assertTrue(reducedSamples.all { it.startMillis == 420 && it.endMillis == 780 })
        assertTrue(reducedSamples.all { kotlin.math.abs(it.alpha - .78f) <= .0001f })
        (0 until SKILL_VFX_END_MILLIS step 10).forEach { elapsed ->
            val roles = authoredRenderableFramePlan(
                definition,
                elapsed,
                false,
                viewportWidth,
                viewportHeight,
            ).map { it.role }.toSet()
            assertTrue(
                "creation earthquake leaked legacy roles at ${elapsed}ms: $roles",
                roles.all { it == AuthoredLayerRole.PRIMARY || it == AuthoredLayerRole.CONTACT },
            )
        }
    }

    @Test
    fun `level five rogue block reads as restrained stab shadow poison trap and vital cut`() {
        val a=SkillCatalog.find("rogue_t01_c01")!!;val b=SkillCatalog.find("rogue_t01_c02")!!;val c=SkillCatalog.find("rogue_t01_c03")!!;val d=SkillCatalog.find("rogue_t01_c04")!!;val e=SkillCatalog.find("rogue_t01_c05")!!
        assertTrue(isQuickStab(a));assertEquals(SemanticVfxAction.PIERCE,semanticVfxPlan(a).action);assertEquals(SkillElement.PHYSICAL,a.element)
        assertTrue(isShadowSlash(b));assertEquals(SemanticVfxAction.CUT,semanticVfxPlan(b).action);assertEquals(SkillElement.DARK,b.element)
        assertTrue(isVenomFangStab(c));assertEquals(SemanticVfxAction.PIERCE,semanticVfxPlan(c).action);assertEquals(SkillElement.POISON,c.element)
        assertTrue(isAnkleTrap(d));assertEquals(SemanticVfxAction.TRAP,semanticVfxPlan(d).action)
        assertTrue(isVitalSlash(e));assertEquals(SemanticVfxAction.CUT,semanticVfxPlan(e).action)
        assertTrue(listOf(a,b,c,d,e).all { it.intensityTier == 1 })
    }

    @Test
    fun `level ten rogue block separates twin fangs ambush poison wire and throat finish`() {
        val a=SkillCatalog.find("rogue_t02_c01")!!;val b=SkillCatalog.find("rogue_t02_c02")!!;val c=SkillCatalog.find("rogue_t02_c03")!!;val d=SkillCatalog.find("rogue_t02_c04")!!;val e=SkillCatalog.find("rogue_t02_c05")!!
        assertTrue(isTwinFangCombo(a));assertEquals(SemanticVfxAction.CROSS_CUT,semanticVfxPlan(a).action)
        assertTrue(isAmbush(b));assertEquals(SemanticVfxAction.CHARGE,semanticVfxPlan(b).action)
        assertTrue(isGreenVenomBurst(c));assertEquals(SemanticVfxAction.BURST,semanticVfxPlan(c).action);assertEquals(SkillElement.POISON,c.element)
        assertTrue(isWireSever(d));assertEquals(SemanticVfxAction.CUT,semanticVfxPlan(d).action)
        assertTrue(isThroatEnd(e));assertEquals(SemanticVfxAction.EXECUTE,semanticVfxPlan(e).action);assertEquals(1,e.hitCount)
    }

    @Test
    fun `level fifteen rogue block honors triple twin and distinct assault trap finisher`() {
        val a=SkillCatalog.find("rogue_t03_c01")!!;val b=SkillCatalog.find("rogue_t03_c02")!!;val c=SkillCatalog.find("rogue_t03_c03")!!;val d=SkillCatalog.find("rogue_t03_c04")!!;val e=SkillCatalog.find("rogue_t03_c05")!!
        assertTrue(isTripleStab(a));assertEquals(3,a.hitCount);assertEquals(SemanticVfxAction.PIERCE,semanticVfxPlan(a).action)
        assertTrue(isAfterimageAssault(b));assertEquals(SemanticVfxAction.CHARGE,semanticVfxPlan(b).action)
        assertTrue(isVenomTwinNeedles(c));assertEquals(2,c.hitCount);assertEquals(SkillElement.POISON,c.element)
        assertTrue(isLassoStrike(d));assertEquals(SemanticVfxAction.TRAP,semanticVfxPlan(d).action)
        assertTrue(isHeartStab(e));assertEquals(SemanticVfxAction.EXECUTE,semanticVfxPlan(e).action);assertEquals(1,e.hitCount)
    }

    @Test
    fun `level twenty rogue block distinguishes dagger leap poison mist blade trap and silent execution`() {
        val a=SkillCatalog.find("rogue_t04_c01")!!;val b=SkillCatalog.find("rogue_t04_c02")!!;val c=SkillCatalog.find("rogue_t04_c03")!!;val d=SkillCatalog.find("rogue_t04_c04")!!;val e=SkillCatalog.find("rogue_t04_c05")!!
        assertTrue(isDaggerFrenzy(a));assertEquals(4,a.hitCount);assertEquals(SemanticVfxAction.FLURRY,semanticVfxPlan(a).action)
        assertTrue(isDarkLeap(b));assertEquals(SemanticVfxAction.DIVE,semanticVfxPlan(b).action);assertEquals(SkillElement.DARK,b.element)
        assertTrue(isPoisonMistBlast(c));assertEquals(SemanticVfxAction.BURST,semanticVfxPlan(c).action);assertEquals(SkillElement.POISON,c.element)
        assertTrue(isBladeTrap(d));assertEquals(SemanticVfxAction.TRAP,semanticVfxPlan(d).action)
        assertTrue(isSilentExecution(e));assertEquals(SemanticVfxAction.EXECUTE,semanticVfxPlan(e).action);assertEquals(1,e.hitCount)
    }

    @Test
    fun `level twenty five rogue block distinguishes crescent shadow spray silver wire and crimson vital`() {
        val a=SkillCatalog.find("rogue_t05_c01")!!;val b=SkillCatalog.find("rogue_t05_c02")!!;val c=SkillCatalog.find("rogue_t05_c03")!!;val d=SkillCatalog.find("rogue_t05_c04")!!;val e=SkillCatalog.find("rogue_t05_c05")!!
        assertTrue(isCrescentDagger(a));assertEquals(SemanticVfxAction.CUT,semanticVfxPlan(a).action)
        assertTrue(isShadowCross(b));assertEquals(SemanticVfxAction.CROSS_CUT,semanticVfxPlan(b).action);assertEquals(SkillElement.DARK,b.element)
        assertTrue(isPoisonSpray(c));assertEquals(SemanticVfxAction.VOLLEY,semanticVfxPlan(c).action);assertEquals(SkillElement.POISON,c.element)
        assertTrue(isSilverWireBinding(d));assertEquals(SemanticVfxAction.TRAP,semanticVfxPlan(d).action)
        assertTrue(isCrimsonVital(e));assertEquals(SemanticVfxAction.EXECUTE,semanticVfxPlan(e).action);assertEquals(1,e.hitCount)
    }

    @Test
    fun `level thirty rogue block distinguishes gale raid corrosion spin and blind spot`() {
        val a=SkillCatalog.find("rogue_t06_c01")!!;val b=SkillCatalog.find("rogue_t06_c02")!!;val c=SkillCatalog.find("rogue_t06_c03")!!;val d=SkillCatalog.find("rogue_t06_c04")!!;val e=SkillCatalog.find("rogue_t06_c05")!!
        assertTrue(isGaleTwinBlades(a));assertEquals(SemanticVfxAction.CROSS_CUT,semanticVfxPlan(a).action);assertEquals(SkillElement.WIND,a.element)
        assertTrue(isNightRaid(b));assertEquals(SemanticVfxAction.CHARGE,semanticVfxPlan(b).action)
        assertTrue(isCorrosionBurst(c));assertEquals(SemanticVfxAction.BURST,semanticVfxPlan(c).action);assertEquals(SkillElement.POISON,c.element)
        assertTrue(isSpinningLasso(d));assertEquals(SemanticVfxAction.SPIN,semanticVfxPlan(d).action)
        assertTrue(isBlindSpotStrike(e));assertEquals(SemanticVfxAction.EXECUTE,semanticVfxPlan(e).action);assertEquals(1,e.hitCount)
    }

    @Test
    fun `level thirty five rogue block honors four daggers shadow poison chain trap and fatal sever`() {
        val a=SkillCatalog.find("rogue_t07_c01")!!;val b=SkillCatalog.find("rogue_t07_c02")!!;val c=SkillCatalog.find("rogue_t07_c03")!!;val d=SkillCatalog.find("rogue_t07_c04")!!;val e=SkillCatalog.find("rogue_t07_c05")!!
        assertTrue(isFourWayDaggers(a));assertEquals(4,a.hitCount);assertEquals(SemanticVfxAction.VOLLEY,semanticVfxPlan(a).action)
        assertTrue(isBlackShadowSlash(b));assertEquals(SemanticVfxAction.CUT,semanticVfxPlan(b).action);assertEquals(SkillElement.DARK,b.element)
        assertTrue(isDeadlyPoisonNeedles(c));assertEquals(SemanticVfxAction.PIERCE,semanticVfxPlan(c).action);assertEquals(SkillElement.POISON,c.element)
        assertTrue(isChainTrap(d));assertEquals(SemanticVfxAction.TRAP,semanticVfxPlan(d).action)
        assertTrue(isFatalSever(e));assertEquals(SemanticVfxAction.EXECUTE,semanticVfxPlan(e).action);assertEquals(1,e.hitCount)
    }

    @Test
    fun `level forty rogue block separates ghost leap serpent steel thread and soul stab`() {
        val a=SkillCatalog.find("rogue_t08_c01")!!;val b=SkillCatalog.find("rogue_t08_c02")!!;val c=SkillCatalog.find("rogue_t08_c03")!!;val d=SkillCatalog.find("rogue_t08_c04")!!;val e=SkillCatalog.find("rogue_t08_c05")!!
        assertTrue(isGhostCarve(a));assertEquals(SemanticVfxAction.FLURRY,semanticVfxPlan(a).action)
        assertTrue(isAfterimageLeap(b));assertEquals(SemanticVfxAction.DIVE,semanticVfxPlan(b).action)
        assertTrue(isSerpentFang(c));assertEquals(3,c.hitCount);assertEquals(SkillElement.POISON,c.element)
        assertTrue(isSteelThreadLine(d));assertEquals(4,d.hitCount);assertEquals(SemanticVfxAction.CROSS_CUT,semanticVfxPlan(d).action)
        assertTrue(isSoulStab(e));assertEquals(SemanticVfxAction.EXECUTE,semanticVfxPlan(e).action);assertEquals(1,e.hitCount)
    }

    @Test
    fun `rogue levels forty five and fifty keep ten exact signatures`() {
        val actions = mapOf(
            "rogue_t09_c01" to SemanticVfxAction.CROSS_CUT,"rogue_t09_c02" to SemanticVfxAction.CHARGE,"rogue_t09_c03" to SemanticVfxAction.BURST,"rogue_t09_c04" to SemanticVfxAction.TRAP,"rogue_t09_c05" to SemanticVfxAction.EXECUTE,
            "rogue_t10_c01" to SemanticVfxAction.VOLLEY,"rogue_t10_c02" to SemanticVfxAction.FLURRY,"rogue_t10_c03" to SemanticVfxAction.CHAIN,"rogue_t10_c04" to SemanticVfxAction.TRAP,"rogue_t10_c05" to SemanticVfxAction.EXECUTE,
        )
        actions.forEach { (id,action) -> assertEquals(id,action,semanticVfxPlan(SkillCatalog.find(id)!!).action) }
        assertEquals(SkillElement.POISON,SkillCatalog.find("rogue_t09_c03")!!.element)
        assertEquals(SkillElement.WIND,SkillCatalog.find("rogue_t10_c01")!!.element)
        assertEquals(1,SkillCatalog.find("rogue_t10_c05")!!.hitCount)
    }

    @Test
    fun `rogue levels fifty five through seventy preserve twenty semantic signatures`() {
        val expected = mapOf(
            "rogue_t11_c01" to SemanticVfxAction.FLURRY,"rogue_t11_c02" to SemanticVfxAction.CHARGE,"rogue_t11_c03" to SemanticVfxAction.BURST,"rogue_t11_c04" to SemanticVfxAction.FLURRY,"rogue_t11_c05" to SemanticVfxAction.EXECUTE,
            "rogue_t12_c01" to SemanticVfxAction.FLURRY,"rogue_t12_c02" to SemanticVfxAction.CHARGE,"rogue_t12_c03" to SemanticVfxAction.PIERCE,"rogue_t12_c04" to SemanticVfxAction.TRAP,"rogue_t12_c05" to SemanticVfxAction.EXECUTE,
            "rogue_t13_c01" to SemanticVfxAction.FLURRY,"rogue_t13_c02" to SemanticVfxAction.CROSS_CUT,"rogue_t13_c03" to SemanticVfxAction.PIERCE,"rogue_t13_c04" to SemanticVfxAction.TRAP,"rogue_t13_c05" to SemanticVfxAction.EXECUTE,
            "rogue_t14_c01" to SemanticVfxAction.FLURRY,"rogue_t14_c02" to SemanticVfxAction.CHARGE,"rogue_t14_c03" to SemanticVfxAction.BURST,"rogue_t14_c04" to SemanticVfxAction.TRAP,"rogue_t14_c05" to SemanticVfxAction.EXECUTE,
        )
        expected.forEach { (id, action) -> assertEquals(id,action,semanticVfxPlan(SkillCatalog.find(id)!!).action) }
        assertEquals(2,SkillCatalog.find("rogue_t12_c03")!!.hitCount)
        assertEquals(SkillElement.POISON,SkillCatalog.find("rogue_t14_c03")!!.element)
    }

    @Test
    fun `rogue levels seventy five through one hundred preserve final thirty signatures`() {
        val expected = mapOf(
            "rogue_t15_c01" to SemanticVfxAction.FLURRY,"rogue_t15_c02" to SemanticVfxAction.FLURRY,"rogue_t15_c03" to SemanticVfxAction.BURST,"rogue_t15_c04" to SemanticVfxAction.TRAP,"rogue_t15_c05" to SemanticVfxAction.EXECUTE,
            "rogue_t16_c01" to SemanticVfxAction.FLURRY,"rogue_t16_c02" to SemanticVfxAction.CHARGE,"rogue_t16_c03" to SemanticVfxAction.CHAIN,"rogue_t16_c04" to SemanticVfxAction.TRAP,"rogue_t16_c05" to SemanticVfxAction.EXECUTE,
            "rogue_t17_c01" to SemanticVfxAction.FLURRY,"rogue_t17_c02" to SemanticVfxAction.FLURRY,"rogue_t17_c03" to SemanticVfxAction.PIERCE,"rogue_t17_c04" to SemanticVfxAction.TRAP,"rogue_t17_c05" to SemanticVfxAction.EXECUTE,
            "rogue_t18_c01" to SemanticVfxAction.FLURRY,"rogue_t18_c02" to SemanticVfxAction.CHARGE,"rogue_t18_c03" to SemanticVfxAction.BURST,"rogue_t18_c04" to SemanticVfxAction.TRAP,"rogue_t18_c05" to SemanticVfxAction.EXECUTE,
            "rogue_t19_c01" to SemanticVfxAction.FLURRY,"rogue_t19_c02" to SemanticVfxAction.FLURRY,"rogue_t19_c03" to SemanticVfxAction.PIERCE,"rogue_t19_c04" to SemanticVfxAction.TRAP,"rogue_t19_c05" to SemanticVfxAction.EXECUTE,
            "rogue_t20_c01" to SemanticVfxAction.FLURRY,"rogue_t20_c02" to SemanticVfxAction.FLURRY,"rogue_t20_c03" to SemanticVfxAction.BURST,"rogue_t20_c04" to SemanticVfxAction.TRAP,"rogue_t20_c05" to SemanticVfxAction.EXECUTE,
        )
        expected.forEach { (id, action) -> assertEquals(id,action,semanticVfxPlan(SkillCatalog.find(id)!!).action) }
        assertEquals(SkillElement.POISON,SkillCatalog.find("rogue_t20_c03")!!.element)
        assertEquals(1,SkillCatalog.find("rogue_t20_c05")!!.hitCount)
        assertEquals(5,SkillCatalog.find("rogue_t20_c01")!!.intensityTier)
    }

    @Test
    fun `level five ranger block uses restrained precision fan breeze trap and moonlight shots`() {
        val a=SkillCatalog.find("ranger_t01_c01")!!;val b=SkillCatalog.find("ranger_t01_c02")!!;val c=SkillCatalog.find("ranger_t01_c03")!!;val d=SkillCatalog.find("ranger_t01_c04")!!;val e=SkillCatalog.find("ranger_t01_c05")!!
        assertTrue(isAimedShot(a));assertEquals(1,a.hitCount);assertEquals(SemanticVfxAction.SHOT,semanticVfxPlan(a).action);assertEquals(ModularVfxArchetype.PROJECTILE,semanticVfxPlan(a).recipe.archetype)
        assertTrue(isTripleArrow(b));assertEquals(3,b.hitCount);assertEquals(SemanticVfxAction.VOLLEY,semanticVfxPlan(b).action);assertEquals(SemanticVfxFlow.OUTSIDE_IN,semanticVfxPlan(b).flow);assertEquals(ModularVfxArchetype.PROJECTILE,semanticVfxPlan(b).recipe.archetype)
        assertTrue(isBreezeArrow(c));assertEquals(SemanticVfxAction.SHOT,semanticVfxPlan(c).action);assertEquals(SkillElement.WIND,c.element)
        assertTrue(isThornTrap(d));assertEquals(SemanticVfxAction.TRAP,semanticVfxPlan(d).action);assertEquals(SkillElement.EARTH,d.element)
        assertTrue(isMoonlightArrow(e));assertEquals(SemanticVfxAction.SHOT,semanticVfxPlan(e).action);assertEquals(SkillElement.COSMIC,e.element)
        assertTrue(listOf(a,b,c,d,e).all { it.intensityTier == 1 })
    }

    @Test
    fun `level ten ranger block separates pierce rapid gust wolf and stardust`() {
        val a=SkillCatalog.find("ranger_t02_c01")!!;val b=SkillCatalog.find("ranger_t02_c02")!!;val c=SkillCatalog.find("ranger_t02_c03")!!;val d=SkillCatalog.find("ranger_t02_c04")!!;val e=SkillCatalog.find("ranger_t02_c05")!!
        assertTrue(isPiercingShot(a));assertEquals(SemanticVfxAction.PIERCE,semanticVfxPlan(a).action)
        assertTrue(isRapidShot(b));assertEquals(3,b.hitCount);assertEquals(SemanticVfxAction.VOLLEY,semanticVfxPlan(b).action);assertEquals(SemanticVfxFlow.OUTSIDE_IN,semanticVfxPlan(b).flow)
        assertTrue(isGustBowstring(c));assertEquals(3,c.hitCount);assertEquals(SemanticVfxAction.VOLLEY,semanticVfxPlan(c).action);assertEquals(SkillElement.WIND,c.element)
        assertTrue(isWolfFang(d));assertEquals(SemanticVfxAction.DIVE,semanticVfxPlan(d).action)
        assertTrue(isStardustShot(e));assertEquals(SemanticVfxAction.SHOT,semanticVfxPlan(e).action);assertEquals(SkillElement.COSMIC,e.element)
    }

    @Test
    fun `ranger levels fifteen through fifty preserve forty distinct semantic signatures`() {
        val expected = mapOf(
            "ranger_t03_c01" to SemanticVfxAction.PIERCE,"ranger_t03_c02" to SemanticVfxAction.VOLLEY,"ranger_t03_c03" to SemanticVfxAction.CUT,"ranger_t03_c04" to SemanticVfxAction.TRAP,"ranger_t03_c05" to SemanticVfxAction.PIERCE,
            "ranger_t04_c01" to SemanticVfxAction.PIERCE,"ranger_t04_c02" to SemanticVfxAction.VOLLEY,"ranger_t04_c03" to SemanticVfxAction.SPIN,"ranger_t04_c04" to SemanticVfxAction.TRAP,"ranger_t04_c05" to SemanticVfxAction.VOLLEY,
            "ranger_t05_c01" to SemanticVfxAction.SHOT,"ranger_t05_c02" to SemanticVfxAction.VOLLEY,"ranger_t05_c03" to SemanticVfxAction.PIERCE,"ranger_t05_c04" to SemanticVfxAction.TRAP,"ranger_t05_c05" to SemanticVfxAction.SHOT,
            "ranger_t06_c01" to SemanticVfxAction.SHOT,"ranger_t06_c02" to SemanticVfxAction.VOLLEY,"ranger_t06_c03" to SemanticVfxAction.VOLLEY,"ranger_t06_c04" to SemanticVfxAction.DESCEND,"ranger_t06_c05" to SemanticVfxAction.SHOT,
            "ranger_t07_c01" to SemanticVfxAction.PIERCE,"ranger_t07_c02" to SemanticVfxAction.VOLLEY,"ranger_t07_c03" to SemanticVfxAction.VOLLEY,"ranger_t07_c04" to SemanticVfxAction.TRAP,"ranger_t07_c05" to SemanticVfxAction.SHOT,
            "ranger_t08_c01" to SemanticVfxAction.SHOT,"ranger_t08_c02" to SemanticVfxAction.VOLLEY,"ranger_t08_c03" to SemanticVfxAction.SPIN,"ranger_t08_c04" to SemanticVfxAction.TRAP,"ranger_t08_c05" to SemanticVfxAction.DESCEND,
            "ranger_t09_c01" to SemanticVfxAction.SHOT,"ranger_t09_c02" to SemanticVfxAction.VOLLEY,"ranger_t09_c03" to SemanticVfxAction.VOLLEY,"ranger_t09_c04" to SemanticVfxAction.DESCEND,"ranger_t09_c05" to SemanticVfxAction.VOLLEY,
            "ranger_t10_c01" to SemanticVfxAction.SHOT,"ranger_t10_c02" to SemanticVfxAction.VOLLEY,"ranger_t10_c03" to SemanticVfxAction.VOLLEY,"ranger_t10_c04" to SemanticVfxAction.DIVE,"ranger_t10_c05" to SemanticVfxAction.PIERCE,
        )
        expected.forEach { (id,action) -> assertEquals(id,action,semanticVfxPlan(SkillCatalog.find(id)!!).action) }
        assertEquals(5,SkillCatalog.find("ranger_t03_c02")!!.hitCount)
        assertEquals(7,SkillCatalog.find("ranger_t07_c02")!!.hitCount)
        assertEquals(SemanticVfxFlow.OUTSIDE_IN,semanticVfxPlan(SkillCatalog.find("ranger_t09_c03")!!).flow)
    }

    @Test
    fun `ranger levels fifty five through one hundred preserve final fifty signatures`() {
        val rows = listOf(
            listOf(SemanticVfxAction.PIERCE,SemanticVfxAction.VOLLEY,SemanticVfxAction.BURST,SemanticVfxAction.TRAP,SemanticVfxAction.SHOT),
            listOf(SemanticVfxAction.SHOT,SemanticVfxAction.VOLLEY,SemanticVfxAction.VOLLEY,SemanticVfxAction.DIVE,SemanticVfxAction.SHOT),
            listOf(SemanticVfxAction.SHOT,SemanticVfxAction.VOLLEY,SemanticVfxAction.SPIN,SemanticVfxAction.DIVE,SemanticVfxAction.PIERCE),
            listOf(SemanticVfxAction.SHOT,SemanticVfxAction.VOLLEY,SemanticVfxAction.CUT,SemanticVfxAction.TRAP,SemanticVfxAction.DESCEND),
            listOf(SemanticVfxAction.SHOT,SemanticVfxAction.VOLLEY,SemanticVfxAction.VOLLEY,SemanticVfxAction.DIVE,SemanticVfxAction.BURST),
            listOf(SemanticVfxAction.PIERCE,SemanticVfxAction.VOLLEY,SemanticVfxAction.CUT,SemanticVfxAction.DIVE,SemanticVfxAction.VOLLEY),
            listOf(SemanticVfxAction.SHOT,SemanticVfxAction.VOLLEY,SemanticVfxAction.COLLAPSE,SemanticVfxAction.DIVE,SemanticVfxAction.PIERCE),
            listOf(SemanticVfxAction.SHOT,SemanticVfxAction.VOLLEY,SemanticVfxAction.BURST,SemanticVfxAction.TRAP,SemanticVfxAction.DESCEND),
            listOf(SemanticVfxAction.SHOT,SemanticVfxAction.VOLLEY,SemanticVfxAction.PIERCE,SemanticVfxAction.DIVE,SemanticVfxAction.BEAM),
            listOf(SemanticVfxAction.SHOT,SemanticVfxAction.VOLLEY,SemanticVfxAction.BURST,SemanticVfxAction.DIVE,SemanticVfxAction.PIERCE),
        )
        rows.forEachIndexed { row, actions -> actions.forEachIndexed { col, action ->
            val id="ranger_t${(row+11).toString().padStart(2,'0')}_c${(col+1).toString().padStart(2,'0')}"
            assertEquals(id,action,semanticVfxPlan(SkillCatalog.find(id)!!).action)
        } }
        assertEquals(SemanticVfxFlow.OUTSIDE_IN,semanticVfxPlan(SkillCatalog.find("ranger_t12_c03")!!).flow)
        assertEquals(ModularVfxArchetype.PROJECTILE,semanticVfxPlan(SkillCatalog.find("ranger_t12_c03")!!).recipe.archetype)
        assertEquals(5,SkillCatalog.find("ranger_t19_c02")!!.hitCount)
        assertEquals(5,SkillCatalog.find("ranger_t20_c02")!!.intensityTier)
    }

    @Test
    fun `mage opening twenty skills separate elemental projectile impact and field silhouettes`() {
        val rows=listOf(
            listOf(SemanticVfxAction.SHOT,SemanticVfxAction.SHOT,SemanticVfxAction.DESCEND,SemanticVfxAction.SHOT,SemanticVfxAction.BURST),
            listOf(SemanticVfxAction.SHOT,SemanticVfxAction.PIERCE,SemanticVfxAction.CHAIN,SemanticVfxAction.BURST,SemanticVfxAction.SHOT),
            listOf(SemanticVfxAction.SPIN,SemanticVfxAction.BURST,SemanticVfxAction.VOLLEY,SemanticVfxAction.CUT,SemanticVfxAction.BURST),
            listOf(SemanticVfxAction.BURST,SemanticVfxAction.PIERCE,SemanticVfxAction.SHOT,SemanticVfxAction.BURST,SemanticVfxAction.VOLLEY),
        )
        rows.forEachIndexed { row,actions -> actions.forEachIndexed { col,action -> val id="mage_t${(row+1).toString().padStart(2,'0')}_c${(col+1).toString().padStart(2,'0')}";assertEquals(id,action,semanticVfxPlan(SkillCatalog.find(id)!!).action) } }
        assertEquals(3,SkillCatalog.find("mage_t03_c03")!!.hitCount)
        assertEquals(SkillElement.ARCANE,SkillCatalog.find("mage_t03_c04")!!.element)
        assertEquals(SkillElement.COSMIC,SkillCatalog.find("mage_t04_c05")!!.element)
    }

    @Test
    fun `mage direct attack naming removes passive or control ambiguity`() {
        assertEquals("홍염 연폭",SkillCatalog.find("mage_t07_c01")!!.name)
        assertEquals("공간 왜곡파",SkillCatalog.find("mage_t10_c04")!!.name)
        assertEquals("신격 뇌전 연격",SkillCatalog.find("mage_t17_c03")!!.name)
        assertEquals("절대빙옥 파쇄",SkillCatalog.find("mage_t18_c02")!!.name)
    }

    @Test
    fun `mage levels twenty five through one hundred keep elemental action grammar`() {
        val exact=mapOf(
            "mage_t05_c01" to SemanticVfxAction.ASCEND,"mage_t05_c02" to SemanticVfxAction.VOLLEY,"mage_t05_c03" to SemanticVfxAction.CHAIN,"mage_t05_c04" to SemanticVfxAction.SPIN,"mage_t05_c05" to SemanticVfxAction.BURST,
            "mage_t06_c01" to SemanticVfxAction.ASCEND,"mage_t06_c02" to SemanticVfxAction.PIERCE,"mage_t06_c03" to SemanticVfxAction.BURST,"mage_t06_c04" to SemanticVfxAction.CUT,"mage_t06_c05" to SemanticVfxAction.DESCEND,
            "mage_t10_c01" to SemanticVfxAction.ASCEND,"mage_t10_c02" to SemanticVfxAction.PIERCE,"mage_t10_c03" to SemanticVfxAction.BURST,"mage_t10_c04" to SemanticVfxAction.COLLAPSE,"mage_t10_c05" to SemanticVfxAction.BURST,
            "mage_t15_c01" to SemanticVfxAction.DIVE,"mage_t15_c02" to SemanticVfxAction.BURST,"mage_t15_c03" to SemanticVfxAction.DESCEND,"mage_t15_c04" to SemanticVfxAction.CHAIN,"mage_t15_c05" to SemanticVfxAction.COLLAPSE,
            "mage_t20_c01" to SemanticVfxAction.ASCEND,"mage_t20_c02" to SemanticVfxAction.BURST,"mage_t20_c03" to SemanticVfxAction.VOLLEY,"mage_t20_c04" to SemanticVfxAction.COLLAPSE,"mage_t20_c05" to SemanticVfxAction.COLLAPSE,
        )
        exact.forEach { (id,action) -> assertEquals(id,action,semanticVfxPlan(SkillCatalog.find(id)!!).action) }
        assertEquals(3,SkillCatalog.find("mage_t15_c04")!!.hitCount)
        assertEquals(SkillElement.COSMIC,SkillCatalog.find("mage_t14_c05")!!.element)
        assertEquals(SkillElement.COSMIC,SkillCatalog.find("mage_t19_c05")!!.element)
    }

    @Test
    fun `cleric catalog names describe direct attacks and final rays stay beams`() {
        val names=mapOf("cleric_t02_c03" to "악령 파쇄","cleric_t02_c05" to "천사의 깃날","cleric_t13_c01" to "순교자의 광선","cleric_t14_c03" to "퇴마 성역폭진","cleric_t15_c03" to "지옥문 붕괴","cleric_t15_c05" to "영혼왕 연격","cleric_t16_c01" to "세계수 성광포","cleric_t16_c03" to "만마 퇴마격","cleric_t17_c03" to "악신 파쇄","cleric_t18_c01" to "영원의 광주","cleric_t18_c03" to "세계 정화폭진","cleric_t19_c03" to "천지 퇴마광","cleric_t20_c01" to "창세 성광포")
        names.forEach { (id,name) -> assertEquals(id,name,SkillCatalog.find(id)!!.name) }
        listOf("cleric_t03_c01","cleric_t05_c01","cleric_t09_c01","cleric_t11_c01","cleric_t14_c01","cleric_t19_c01","cleric_t20_c03").forEach { id -> assertEquals(id,SemanticVfxAction.BEAM,semanticVfxPlan(SkillCatalog.find(id)!!).action) }
        assertEquals(3,SkillCatalog.find("cleric_t03_c01")!!.hitCount)
        assertEquals(1,SkillCatalog.find("cleric_t20_c03")!!.hitCount)
    }

    @Test
    fun `paladin catalog names and choreography keep the promised holy attack shape`() {
        val names = mapOf(
            "paladin_t01_c03" to "전투망치 강타",
            "paladin_t02_c05" to "맹세의 검격",
            "paladin_t03_c03" to "기사의 망치 강타",
            "paladin_t05_c03" to "황금 망치 강타",
            "paladin_t06_c05" to "왕가의 검격",
            "paladin_t10_c03" to "정의의 대망치 강하",
            "paladin_t11_c03" to "별철 성추 강타",
            "paladin_t12_c04" to "찬란한 검풍",
            "paladin_t14_c05" to "영원 서약참",
            "paladin_t15_c01" to "태양왕의 검격",
            "paladin_t15_c04" to "황금 새벽광",
            "paladin_t16_c05" to "기사왕 천공돌격",
            "paladin_t17_c04" to "창세 새벽광",
            "paladin_t19_c04" to "영원 여명광",
        )
        names.forEach { (id, name) -> assertEquals(id, name, SkillCatalog.find(id)!!.name) }

        val actions = mapOf(
            "paladin_t03_c01" to SemanticVfxAction.FLURRY,
            "paladin_t04_c05" to SemanticVfxAction.CHARGE,
            "paladin_t05_c05" to SemanticVfxAction.FLURRY,
            "paladin_t06_c01" to SemanticVfxAction.SPIN,
            "paladin_t08_c01" to SemanticVfxAction.CROSS_CUT,
            "paladin_t09_c05" to SemanticVfxAction.CHARGE,
            "paladin_t12_c01" to SemanticVfxAction.FLURRY,
            "paladin_t14_c04" to SemanticVfxAction.ASCEND,
            "paladin_t14_c05" to SemanticVfxAction.FLURRY,
            "paladin_t15_c04" to SemanticVfxAction.BEAM,
            "paladin_t16_c05" to SemanticVfxAction.CHARGE,
            "paladin_t18_c01" to SemanticVfxAction.CROSS_CUT,
            "paladin_t19_c05" to SemanticVfxAction.CHARGE,
            "paladin_t20_c05" to SemanticVfxAction.FLURRY,
        )
        actions.forEach { (id, action) -> assertEquals(id, action, semanticVfxPlan(SkillCatalog.find(id)!!).action) }
        assertEquals(3, SkillCatalog.find("paladin_t06_c01")!!.hitCount)
        assertEquals(3, SkillCatalog.find("paladin_t12_c01")!!.hitCount)
        assertEquals(2, SkillCatalog.find("paladin_t18_c01")!!.hitCount)
    }

    @Test
    fun `every consecutive attack alternates its visual direction`() {
        SkillCatalog.all.filter { it.hitCount > 1 }.forEach { definition ->
            val directions = definition.hitTimingsMillis.indices.map { hitIndex ->
                semanticHitChoreography(definition, hitIndex).visualDirection
            }

            assertTrue(
                "${definition.catalogId} ${definition.name} repeats a visual direction: $directions",
                directions.zipWithNext().all { (before, after) -> before == -after },
            )
        }
    }

    @Test
    fun `ordinary attacks do not rotate completed images against their authored direction`() {
        val cut = SkillCatalog.find("warrior_t01_c01")!!
        val descend = SkillCatalog.find("warrior_t01_c02")!!
        val pierce = SkillCatalog.find("warrior_t01_c03")!!

        assertEquals(SemanticVfxFlow.LEFT_TO_RIGHT, semanticVfxPlan(cut).flow)
        assertEquals(SemanticVfxFlow.TOP_TO_BOTTOM, semanticVfxPlan(descend).flow)
        assertEquals(SemanticVfxFlow.LEFT_TO_RIGHT, semanticVfxPlan(pierce).flow)
        assertEquals(1f, semanticHitChoreography(cut, 0).mirrorDirection)
        assertEquals(1f, semanticHitChoreography(descend, 0).mirrorDirection)
        assertEquals(1f, semanticHitChoreography(pierce, 0).mirrorDirection)
    }

    @Test
    fun `projectiles converge from varied edges point to center and shrink on impact`() {
        val projectiles = SkillCatalog.all.filter { definition ->
            semanticVfxPlan(definition).action in setOf(
                SemanticVfxAction.SHOT,
                SemanticVfxAction.PIERCE,
                SemanticVfxAction.CHAIN,
            )
        }

        assertTrue(projectiles.isNotEmpty())
        assertTrue(projectiles.flatMap { definition ->
            definition.hitTimingsMillis.indices.map { centerConvergenceOrigin(definition, it) }
        }.toSet().size >= 6)
        projectiles.forEach { definition ->
            definition.hitTimingsMillis.indices.forEach { hitIndex ->
                assertTrue(usesCenterConvergence(definition, hitIndex))
                val origin = centerConvergenceOrigin(definition, hitIndex)
                val start = centerConvergenceFrame(0f, origin, reducedMotion = false)
                val middle = centerConvergenceFrame(0.5f, origin, reducedMotion = false)
                val end = centerConvergenceFrame(1f, origin, reducedMotion = false)

                assertEquals(origin.xFraction, start.xFraction, 0.0001f)
                assertEquals(origin.yFraction, start.yFraction, 0.0001f)
                assertEquals(0.50f, end.xFraction, 0.0001f)
                assertEquals(0.60f, end.yFraction, 0.0001f)
                assertTrue(start.scale > middle.scale)
                assertTrue(middle.scale > end.scale)
                assertTrue(start.rotationDegrees.isFinite())
            }
        }
    }

    @Test
    fun `warrior frenzy finishes in place instead of using projectile convergence`() {
        val frenzy = SkillCatalog.find("warrior_t01_c05")!!
        val finalIndex = frenzy.hitTimingsMillis.lastIndex

        assertEquals(false, usesCenterConvergence(frenzy, finalIndex))
        assertEquals(ModularVfxArchetype.WARRIOR, semanticHitChoreography(frenzy, finalIndex).recipe.archetype)
        assertEquals(false, usesCenterConvergence(frenzy, finalIndex - 1))
    }

    @Test
    fun `warrior catalog combos keep their authored hit count`() {
        val promised = mapOf(
            "warrior_t01_c05" to 3,
            "warrior_t02_c05" to 3,
            "warrior_t03_c05" to 5,
            "warrior_t04_c05" to 5,
            "warrior_t05_c05" to 5,
            "warrior_t06_c05" to 6,
            "warrior_t07_c01" to 1,
            "warrior_t07_c05" to 4,
            "warrior_t08_c05" to 7,
            "warrior_t09_c05" to 6,
            "warrior_t10_c05" to 7,
            "warrior_t11_c01" to 1,
            "warrior_t11_c05" to 7,
            "warrior_t12_c05" to 7,
            "warrior_t13_c05" to 6,
            "warrior_t14_c05" to 10,
            "warrior_t15_c05" to 8,
            "warrior_t16_c05" to 12,
            "warrior_t17_c05" to 9,
            "warrior_t18_c05" to 8,
            "warrior_t19_c05" to 10,
            "warrior_t20_c05" to 12,
        )
        promised.forEach { (catalogId, count) ->
            val definition = SkillCatalog.find(catalogId)!!
            assertEquals(catalogId, count, definition.hitCount)
            assertEquals(100, definition.hitWeights.sum())
            assertEquals(count, definition.hitTimingsMillis.size)
            assertTrue(definition.hitTimingsMillis.last() <= 900)
        }
    }

    @Test
    fun `late warrior combos use more distinct choreography than early combos`() {
        fun frames(catalogId: String) = SkillCatalog.find(catalogId)!!.let { definition ->
            definition.hitTimingsMillis.indices.map { semanticHitChoreography(definition, it) }
        }
        val early = frames("warrior_t01_c05")
        val late = frames("warrior_t20_c05")

        assertTrue(late.map { it.recipe }.distinct().size > early.map { it.recipe }.distinct().size)
        assertTrue(late.map { it.rotationDegrees }.distinct().size >= 8)
        assertTrue(late.map { it.xOffsetFraction to it.yOffsetFraction }.distinct().size >= 8)
        assertEquals(ModularVfxArchetype.WARRIOR, late.last().recipe.archetype)
        assertEquals(8, late.last().recipe.index)
    }

    @Test
    fun `warrior finishers escalate to dedicated late game resources`() {
        val early = SkillCatalog.find("warrior_t01_c05")!!
        val mid = SkillCatalog.find("warrior_t09_c05")!!
        val meteor = SkillCatalog.find("warrior_t15_c05")!!
        val ultimate = SkillCatalog.find("warrior_t20_c05")!!

        assertEquals(4, warriorFinisherResourceIndex(early))
        assertEquals(4, warriorFinisherResourceIndex(mid))
        assertEquals(6, warriorFinisherResourceIndex(meteor))
        assertEquals(10, warriorFinisherResourceIndex(ultimate))
    }

    @Test
    fun `skill visuals grow monotonically from level five to level one hundred`() {
        val definitions = (1..20).map { tier ->
            SkillCatalog.all.first { it.unlockLevel == tier * 5 }
        }
        val profiles = definitions.map(::skillVfxIntensityProfile)

        assertEquals(1, profiles.first().tier)
        assertEquals(5, profiles.last().tier)
        assertTrue(profiles.zipWithNext().all { (before, after) -> before.primaryScale <= after.primaryScale })
        assertTrue(profiles.zipWithNext().all { (before, after) -> before.primaryAlpha <= after.primaryAlpha })
        assertTrue(profiles.zipWithNext().all { (before, after) -> before.backdropScale <= after.backdropScale })
        assertTrue(profiles.zipWithNext().all { (before, after) -> before.impactScale <= after.impactScale })
        assertTrue(profiles.zipWithNext().all { (before, after) -> before.finalLayerCount <= after.finalLayerCount })
        assertEquals(2, profiles.first().finalLayerCount)
        assertEquals(5, profiles.last().finalLayerCount)
    }

    @Test
    fun `early skills use four distinct roles while late skills earn more layers`() {
        val early = SkillCatalog.all.filter { it.unlockLevel <= 20 }
        val late = SkillCatalog.all.filter { it.unlockLevel >= 45 }

        assertTrue(early.isNotEmpty())
        assertTrue(early.all { it.finisher == SkillFinisher.NONE })
        assertTrue(late.isNotEmpty())
        assertTrue(late.all { it.finisher != SkillFinisher.NONE })
        assertTrue(early.filterNot(::shouldKeepLegacyPrimary).all {
            modularLayerCount(it, isFinal = true) in 4..5
        })
        assertTrue(late.all { modularLayerCount(it, isFinal = true) >= 3 })
        assertTrue(SkillCatalog.all.filter { it.unlockLevel >= 85 }.filterNot(::shouldKeepLegacyPrimary).all {
            modularLayerCount(it, isFinal = true) >= if (warriorNonSlashColumn(it) == null) 6 else 5
        })
    }

    @Test
    fun `mage names choose matching spell actions instead of catalog fallbacks`() {
        fun action(name: String) = semanticVfxPlan(SkillCatalog.all.single { it.name == name }).action

        assertEquals(SemanticVfxAction.PIERCE, action("얼음 창"))
        assertEquals(SemanticVfxAction.SPIN, action("눈보라"))
        assertEquals(SemanticVfxAction.DIVE, action("혜성 충돌"))
        assertEquals(SemanticVfxAction.DESCEND, action("전격"))
        assertEquals(SemanticVfxAction.ASCEND, action("태양을 삼킨 불꽃"))
        assertEquals(SemanticVfxAction.SHOT, action("유성 조각"))
    }

    @Test
    fun `explicit number words own the hit count across every class`() {
        val promised = mapOf(
            "세 갈래 화살" to 3,
            "삼연 찌르기" to 3,
            "맹독 쌍침" to 2,
            "오연사" to 5,
            "일곱 화살" to 7,
            "삼중 광선" to 3,
            "삼중 성검" to 3,
            "십자 베기" to 2,
        )
        promised.forEach { (name, hits) ->
            assertEquals(name, hits, SkillCatalog.all.single { it.name == name }.hitCount)
        }
    }

    @Test
    fun `resolved semantic actions drive camera direction and long combos stay restrained`() {
        val charge = SkillCatalog.find("warrior_t01_c03")!!
        val burst = SkillCatalog.all.single { it.name == "별가루 폭발" }
        val longCombo = SkillCatalog.find("warrior_t16_c05")!!

        assertEquals(SemanticVfxAction.CHARGE, semanticVfxPlan(charge).action)
        assertEquals(0f, skillCameraFrame(436, burst).translationX)
        assertEquals(0f, skillCameraFrame(436, burst).translationY)
        val endpoints = skillPresentationHits(longCombo).map { it.timingMillis }
        longCombo.hitTimingsMillis.filterNot(endpoints::contains).forEach { timing ->
            if (endpoints.none { timing + 16 in (it - 30)..(it + 120) }) {
                assertEquals(SkillCameraFrame(), skillCameraFrame(timing + 16, longCombo))
            }
        }
    }

    @Test
    fun `catalog overrides preserve the promised attack shape`() {
        val expected = mapOf(
            "rogue_t03_c01" to SemanticVfxAction.PIERCE,
            "ranger_t01_c02" to SemanticVfxAction.VOLLEY,
            "ranger_t02_c02" to SemanticVfxAction.VOLLEY,
            "ranger_t04_c02" to SemanticVfxAction.VOLLEY,
            "ranger_t07_c02" to SemanticVfxAction.VOLLEY,
            "ranger_t12_c02" to SemanticVfxAction.VOLLEY,
            "ranger_t13_c02" to SemanticVfxAction.VOLLEY,
            "cleric_t03_c01" to SemanticVfxAction.BEAM,
            "cleric_t05_c01" to SemanticVfxAction.BEAM,
            "cleric_t09_c01" to SemanticVfxAction.BEAM,
            "cleric_t11_c01" to SemanticVfxAction.BEAM,
            "cleric_t14_c01" to SemanticVfxAction.BEAM,
            "cleric_t19_c01" to SemanticVfxAction.BEAM,
            "mage_t17_c03" to SemanticVfxAction.VOLLEY,
            "ranger_t18_c03" to SemanticVfxAction.BURST,
            "paladin_t20_c05" to SemanticVfxAction.FLURRY,
        )
        expected.forEach { (catalogId, action) ->
            val definition = SkillCatalog.all.single { it.catalogId == catalogId }
            assertEquals(catalogId, action, semanticVfxPlan(definition).action)
        }
        assertEquals(3, SkillCatalog.all.single { it.catalogId == "mage_t15_c04" }.hitCount)
        SkillCatalog.all.filter { it.motion == SkillMotion.DASH_IMPACT }.forEach { definition ->
            assertTrue(
                "${definition.catalogId} ${definition.name} -> ${semanticVfxPlan(definition).action}",
                semanticVfxPlan(definition).action in setOf(
                    SemanticVfxAction.CHARGE,
                    SemanticVfxAction.FLURRY,
                    SemanticVfxAction.CUT,
                ),
            )
        }
    }

    @Test
    fun `reduce motion keeps projectiles static and steps energy at each hit`() {
        val projectile = SkillCatalog.all.single { it.name == "마력탄" }
        val origin = centerConvergenceOrigin(projectile, 0)
        val frame = centerConvergenceFrame(0f, origin, reducedMotion = true)
        assertEquals(0.50f, frame.xFraction, 0.0001f)
        assertEquals(0.60f, frame.yFraction, 0.0001f)
        assertEquals(1f, frame.scale, 0.0001f)
        assertEquals(0f, frame.rotationDegrees, 0.0001f)

        val firstHit = projectile.hitTimingsMillis.first()
        assertEquals(1f, skillEnergyFraction(firstHit - 1, 1f, 0f, projectile, reducedMotion = true))
        assertTrue(skillEnergyFraction(firstHit, 1f, 0f, projectile, reducedMotion = true) < 1f)
    }
}
