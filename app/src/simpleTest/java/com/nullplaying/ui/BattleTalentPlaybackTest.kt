package com.nullplaying.ui

import com.nullplaying.engine.HeroPathCatalog
import com.nullplaying.localization.AppLanguage
import com.nullplaying.model.BattleActionKind
import com.nullplaying.model.BattleOutcome
import com.nullplaying.model.BattleRound
import com.nullplaying.model.BattleRoundAction
import com.nullplaying.model.BattleSide
import com.nullplaying.model.BattleSkillSnapshot
import com.nullplaying.model.BattleTalentEffectTrace
import com.nullplaying.model.HERO_PATH_TREE_VERSION
import com.nullplaying.model.HeroPathBattleNodeSnapshot
import com.nullplaying.model.HeroPathBattleSnapshot
import com.nullplaying.model.HeroPathBranch
import com.nullplaying.model.HeroPathChoiceStance
import com.nullplaying.model.HeroPathNodeDefinition
import com.nullplaying.model.HeroPathNodeSlot
import com.nullplaying.model.NormalizedBattleProjection
import com.nullplaying.model.ProjectionBattleResult
import com.nullplaying.remote.BattleQaNarrative
import com.nullplaying.remote.BattleQaScene
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BattleTalentPlaybackTest {
    private val branch = HeroPathBranch.WARRIOR_BERSERKER
    private val foundation = node(branch, HeroPathNodeSlot.FOUNDATION_A)
    private val specialA = node(branch, HeroPathNodeSlot.SPECIAL_A)
    private val specialB = node(branch, HeroPathNodeSlot.SPECIAL_B)
    private val core = node(HeroPathBranch.WARRIOR_BULWARK, HeroPathNodeSlot.CORE)

    @Test
    fun `V2 opening prepares real passives for both owners without pretending specials fired`() {
        val fight = battle(
            user = projection("Blue", foundation, specialA, specialB, core),
            opponent = projection("Red", foundation, specialA),
        )
        AppLanguage.entries.forEach { language ->
            val plan = battlePlaybackPlan(fight, narrative(language))
            assertEquals(BattlePlaybackBeatKind.OPENING, plan.sequence.first().kind)
            val preparations = plan.sequence.filter { it.kind == BattlePlaybackBeatKind.OPENING_TRAIT }
            assertEquals(listOf(BattleSide.USER, BattleSide.OPPONENT), preparations.map { it.actorSide })
            assertTrue(preparations.all { it.traitId == foundation.nodeId })
            assertTrue(preparations.all { "activated" !in it.text && "발동" !in it.text && "発動" !in it.text })
            assertEquals(3, plan.sequence.indexOfFirst { it.kind == BattlePlaybackBeatKind.ACTION })
            assertTrue(plan.sequence.none { it.kind == BattlePlaybackBeatKind.MID_BATTLE_TRAIT })
        }
    }

    @Test
    fun `V2 does not synthesize legacy activations at one third or two thirds`() {
        val rounds = (1..6).map { round(it) }
        val fight = battle(rounds = rounds, user = projection("Blue", specialA).copy(activeTraitIds = listOf("TRAIT_012", "TRAIT_050")))
        val plan = battlePlaybackPlan(fight, narrative())
        assertTrue(plan.sequence.none { it.kind == BattlePlaybackBeatKind.OPENING_TRAIT || it.kind == BattlePlaybackBeatKind.MID_BATTLE_TRAIT })
    }

    @Test
    fun `multiple actual effects attach once each to the owning action and keep skill text unchanged`() {
        val userEffects = listOf(trace(specialA), trace(specialB), trace(specialA))
        val opponentEffects = listOf(trace(specialB))
        val first = round(1).copy(
            userAction = action(BattleSide.USER, userEffects),
            opponentAction = action(BattleSide.OPPONENT, opponentEffects),
            talentEffects = userEffects + opponentEffects,
        )
        val fight = battle(listOf(first, round(2)), projection("Blue", specialA, specialB), projection("Red", specialB))
        val plan = battlePlaybackPlan(fight, narrative())
        val triggers = plan.sequence.filter { it.kind == BattlePlaybackBeatKind.MID_BATTLE_TRAIT }
        assertEquals(listOf(specialA.nodeId, specialB.nodeId, specialB.nodeId), triggers.map { it.traitId })
        assertEquals(listOf(BattleSide.USER, BattleSide.USER, BattleSide.OPPONENT), triggers.map { it.actorSide })
        assertTrue(triggers.all { it.roundIndex == 0 })
        assertEquals(plan.turns, plan.sequence.filter { it.kind == BattlePlaybackBeatKind.ACTION })
        assertTrue(plan.turns.first { it.actorSide == BattleSide.USER }.text.contains("Owned Slash"))
    }

    @Test
    fun `actual proc names are localized rather than replaced by legacy trait categories`() {
        val fight = battle(listOf(round(1).copy(userAction = action(BattleSide.USER, listOf(trace(specialA))))))
        AppLanguage.entries.forEach { language ->
            val text = battlePlaybackPlan(fight, narrative(language)).sequence.single {
                it.kind == BattlePlaybackBeatKind.MID_BATTLE_TRAIT
            }.text
            assertTrue(text.contains(heroPathShortNodeName(specialA).resolve(language)))
            assertTrue(text.contains("Blue"))
            if (language != AppLanguage.KOREAN) assertFalse(text.contains(Regex("[가-힣]")))
        }
    }

    @Test
    fun `proc announcements freeze both gauges at the preceding attack state`() {
        val fight = battle((1..3).map { index ->
            round(index).copy(
                userAction = action(BattleSide.USER, listOf(trace(specialA))),
                opponentAction = action(BattleSide.OPPONENT, listOf(trace(specialA))),
            )
        }, opponent = projection("Red", specialA))
        val plan = battlePlaybackPlan(fight, narrative())
        plan.sequence.filter { it.kind == BattlePlaybackBeatKind.MID_BATTLE_TRAIT }.forEach { beat ->
            assertEquals(beat.userEnergyBefore, beat.userEnergyAfter)
            assertEquals(beat.opponentEnergyBefore, beat.opponentEnergyAfter)
            assertEquals(0, beat.userPower)
            assertEquals(0, beat.opponentPower)
            assertEquals(null, beat.arenaTintSide)
        }
        plan.sequence.zipWithNext().forEach { (before, after) ->
            assertEquals(before.userEnergyAfter, after.userEnergyBefore)
            assertEquals(before.opponentEnergyAfter, after.opponentEnergyBefore)
        }
    }

    @Test
    fun `unowned unknown or wrong class sources do not create talent announcements`() {
        val mageNode = node(HeroPathBranch.MAGE_ELEMENTALIST, HeroPathNodeSlot.SPECIAL_A)
        val effects = listOf(trace(specialB), trace(mageNode), trace(specialA).copy(sourceNodeId = "missing-node"))
        val fight = battle(listOf(round(1).copy(userAction = action(BattleSide.USER, effects))))
        assertTrue(battlePlaybackPlan(fight, narrative()).sequence.none { it.kind == BattlePlaybackBeatKind.MID_BATTLE_TRAIT })
    }

    @Test
    fun `the finishing proc is announced before the final hit and never after zero HP`() {
        val final = round(1).copy(
            userAction = action(BattleSide.USER, listOf(trace(specialA))).copy(damage = 1_000),
            opponentHpAfter = 0,
        )
        val fight = battle(listOf(final)).copy(outcome = BattleOutcome.USER_WIN)
        val plan = battlePlaybackPlan(fight, narrative())
        assertEquals(BattlePlaybackBeatKind.ACTION, plan.sequence.last().kind)
        assertEquals(0, plan.sequence.last().opponentEnergyAfter)
        assertEquals(BattlePlaybackBeatKind.MID_BATTLE_TRAIT, plan.sequence[plan.sequence.lastIndex - 1].kind)
        assertTrue(plan.sequence.dropLast(1).all { it.userEnergyAfter > 0 && it.opponentEnergyAfter > 0 })
    }

    @Test
    fun `round end survival appears after its damage at one HP with correct owner`() {
        val first = round(1).copy(
            opponentAction = action(BattleSide.OPPONENT).copy(damage = 1_100),
            userHpAfter = 1,
            talentEffects = listOf(trace(core).copy(lethalSurvival = true)),
        )
        val second = round(2).copy(userHpBefore = 1, userHpAfter = 1, opponentAction = action(BattleSide.OPPONENT).copy(damage = 0))
        val fight = battle(listOf(first, second), user = projection("Blue", core))
        val plan = battlePlaybackPlan(fight, narrative())
        val index = plan.sequence.indexOfFirst { it.kind == BattlePlaybackBeatKind.MID_BATTLE_TRAIT }
        val survival = plan.sequence[index]
        assertEquals(core.nodeId, survival.traitId)
        assertEquals(BattleSide.USER, survival.actorSide)
        assertEquals(1, survival.userEnergyBefore)
        assertEquals(1, survival.userEnergyAfter)
        assertEquals(BattlePlaybackBeatKind.ACTION, plan.sequence[index - 1].kind)
        assertEquals(1, plan.sequence[index - 1].userEnergyAfter)
        assertTrue(survival.text.contains("survived"))
    }

    @Test
    fun `final simultaneous survival joins final attack text without a ghost activation beat`() {
        val final = round(1).copy(
            userAction = action(BattleSide.USER).copy(damage = 1_100),
            opponentAction = action(BattleSide.OPPONENT).copy(damage = 1_100),
            userHpAfter = 1,
            opponentHpAfter = 0,
            talentEffects = listOf(trace(core).copy(lethalSurvival = true)),
        )
        val fight = battle(listOf(final), user = projection("Blue", core)).copy(outcome = BattleOutcome.USER_WIN)
        val plan = battlePlaybackPlan(fight, narrative())
        assertEquals(BattlePlaybackBeatKind.ACTION, plan.sequence.last().kind)
        assertTrue(plan.sequence.last().text.contains("Blue survived"))
        assertEquals(1, plan.sequence.last().userEnergyAfter)
        assertTrue(plan.sequence.none { it.kind == BattlePlaybackBeatKind.MID_BATTLE_TRAIT })
    }

    @Test
    fun `mirrored survival cores remain owned by both actual survivors`() {
        val simultaneous = round(1).copy(
            userAction = action(BattleSide.USER).copy(damage = 1_100),
            opponentAction = action(BattleSide.OPPONENT).copy(damage = 1_100),
            userHpAfter = 1, opponentHpAfter = 1,
            talentEffects = List(2) { trace(core).copy(lethalSurvival = true) },
        )
        val fight = battle(listOf(simultaneous), projection("Blue", core), projection("Red", core))
        val triggers = battlePlaybackPlan(fight, narrative()).sequence.filter { it.kind == BattlePlaybackBeatKind.MID_BATTLE_TRAIT }
        assertEquals(listOf(BattleSide.USER, BattleSide.OPPONENT), triggers.map { it.actorSide })
        assertTrue(triggers.all { it.userEnergyBefore == 1 && it.opponentEnergyBefore == 1 })
    }

    @Test
    fun `only the dominant stance is prepared and redundant capped passive ranks are omitted`() {
        val otherFoundation = node(HeroPathBranch.WARRIOR_BULWARK, HeroPathNodeSlot.FOUNDATION_A)
        val choice = node(branch, HeroPathNodeSlot.CHOICE_A)
        val ignoredChoice = node(HeroPathBranch.WARRIOR_BULWARK, HeroPathNodeSlot.CHOICE_B)
        val user = projection("Blue", foundation, otherFoundation, choice, ignoredChoice).let { owner ->
            owner.copy(heroPathBattleSnapshot = owner.heroPathBattleSnapshot.copy(
                dominantBranch = branch,
                choiceStance = HeroPathChoiceStance.A,
                nodes = owner.heroPathBattleSnapshot.nodes.map { it.copy(rank = if (it.slot == HeroPathNodeSlot.FOUNDATION_A) 2 else 1) },
            ))
        }
        val prepared = battlePlaybackPlan(battle(user = user), narrative()).sequence.filter { it.kind == BattlePlaybackBeatKind.OPENING_TRAIT }
        assertEquals(setOf(foundation.nodeId, choice.nodeId), prepared.map { it.traitId }.toSet())
    }

    private fun node(branch: HeroPathBranch, slot: HeroPathNodeSlot) =
        HeroPathCatalog.nodes.first { it.branch == branch && it.slot == slot }

    private fun projection(name: String, vararg nodes: HeroPathNodeDefinition) = NormalizedBattleProjection(
        displayName = name,
        skills = listOf(BattleSkillSnapshot(skillId = "owned-slash", displayName = "Owned Slash")),
        heroPathBattleSnapshot = HeroPathBattleSnapshot(
            treeVersion = HERO_PATH_TREE_VERSION,
            ownedSkillIds = listOf("owned-slash"),
            activeCoreNodeId = nodes.firstOrNull { it.slot == HeroPathNodeSlot.CORE }?.nodeId.orEmpty(),
            nodes = nodes.map { node -> HeroPathBattleNodeSnapshot(
                nodeId = node.nodeId, rank = 1, branch = node.branch, effectFamily = node.effectFamily,
                slot = node.slot, effectStage = node.effectStage,
            ) },
        ),
    )

    private fun trace(node: HeroPathNodeDefinition) = BattleTalentEffectTrace(
        sourceNodeId = node.nodeId, effectFamily = node.effectFamily, chargeBefore = 3, chargeAfter = 0,
    )

    private fun action(side: BattleSide, effects: List<BattleTalentEffectTrace> = emptyList()) = BattleRoundAction(
        actor = side, kind = BattleActionKind.SKILL, skillId = "owned-slash", damage = 100, talentEffects = effects,
    )

    private fun round(number: Int) = BattleRound(
        number = number, userHpBefore = 1_100 - number * 100, opponentHpBefore = 1_100 - number * 100,
        userAction = action(BattleSide.USER), opponentAction = action(BattleSide.OPPONENT),
        userHpAfter = 1_000 - number * 100, opponentHpAfter = 1_000 - number * 100,
    )

    private fun battle(
        rounds: List<BattleRound> = listOf(round(1), round(2)),
        user: NormalizedBattleProjection = projection("Blue", specialA),
        opponent: NormalizedBattleProjection = projection("Red"),
    ) = ProjectionBattleResult(battleId = "real-talent-playback", user = user, opponent = opponent, rounds = rounds)

    private fun narrative(language: AppLanguage = AppLanguage.ENGLISH) = BattleQaNarrative(
        languageTag = language.languageTag,
        scenes = listOf(BattleQaScene(text = "Owned Slash connects.")),
    )
}
