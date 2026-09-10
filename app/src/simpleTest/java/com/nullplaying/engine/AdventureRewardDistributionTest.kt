package com.nullplaying.engine

import com.nullplaying.model.AdventureEventItemReward
import com.nullplaying.model.AdventureEventOutcome
import com.nullplaying.model.AdventureEventRewardKind
import com.nullplaying.model.AdventureEventRun
import com.nullplaying.model.AdventureEventStat
import com.nullplaying.model.AdventureJourneyState
import com.nullplaying.model.AdventurePhase
import com.nullplaying.model.EquipmentSlot
import com.nullplaying.model.HeroClass
import com.nullplaying.model.MonsterGrade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdventureRewardDistributionTest {
    @Test(timeout = 60_000L)
    fun `equipment awards conditioned on the fixed success roll reach all slots and preserve schedule state`() {
        val engine = SimpleGameEngine(enableAdventureEvents = true)
        val slots = EquipmentSlot.entries.associateWith { 0 }.toMutableMap()
        val powerRolls = (0..11).associateWith { 0 }.toMutableMap()
        val rarities = linkedMapOf<String, Int>()
        var awardState = 0x5D31_48B7_0926_ACEFL
        var awards = 0

        // Exercise the exact conditional boundary, rather than drawing unconditioned reward seeds.
        // 5,000 eligible equipment-award attempts produce about 450 accepted awards. The main
        // event selector/approach/outcome gates have their own catalog tests; each accepted case
        // here executes the real main-engine equipment generation, equip and reward settlement.
        repeat(5_000) {
            awardState = nextState(awardState)
            if (bounded(awardState, AdventureEventEngine.EQUIPMENT_REWARD_ROLL_BOUND) >=
                AdventureEventEngine.EQUIPMENT_REWARD_ACCEPTED_ROLLS) return@repeat
            val rawRewardState = nextState(awardState) // Existing post-award advance, retained by begin().
            val rewardSeed = AdventureEventEngine.forkRewardSeed(rawRewardState)
            val state = engine.newGame("분포 검증", HeroClass.WARRIOR,
                engine.rollStats(77L).stats, 88L, 0L)
            state.hero.level = 20L
            state.hero.experience = 0L
            state.equipment.forEach { equipment -> equipment.power = 1L }
            val run = AdventureEventRun(
                sequence = 1L,
                eventId = "ruins",
                approachId = "decode",
                startedAt = 0L,
                durationMillis = AdventureEventEngine.ACTION_MILLIS,
                heroLevel = 20L,
                primaryStat = AdventureEventStat.INT,
                secondaryStat = AdventureEventStat.WIS,
                primaryValue = state.hero.stats.intelligence,
                secondaryValue = state.hero.stats.wisdom,
                successBasisPoints = 8_500,
                partialBasisPoints = 1_000,
                roll = 0,
                outcome = AdventureEventOutcome.SUCCESS,
                experienceReward = 0L,
                goldReward = 0L,
                itemReward = AdventureEventItemReward.EQUIPMENT,
                routeDelayMillis = 0L,
                rewardSeed = rewardSeed,
                rewardKind = AdventureEventRewardKind.ITEM,
            )
            state.adventureJourney = AdventureJourneyState(initialized = true, rngState = rawRewardState,
                sequence = 1L, pending = run)
            state.adventurePhase = AdventurePhase.EVENT
            state.actionStartedAt = 0L
            state.actionEndsAt = run.durationMillis
            val combatSeedBefore = state.rngState

            engine.settleOffline(state, run.durationMillis)

            assertEquals(1L, state.totalItemsFound)
            assertEquals(1L, state.adventureJourney.completedEvents)
            assertEquals(AdventurePhase.EVENT_RESULT, state.adventurePhase)
            assertEquals(combatSeedBefore, state.rngState)
            assertEquals("Reward draws must not move the event/schedule stream",
                nextState(nextState(rawRewardState)), state.adventureJourney.rngState)
            assertEquals(run.durationMillis + AdventureEventEngine.MIN_INTERVAL_MILLIS +
                bounded(nextState(rawRewardState),
                    (AdventureEventEngine.MAX_INTERVAL_MILLIS - AdventureEventEngine.MIN_INTERVAL_MILLIS + 1L).toInt()),
                state.adventureJourney.nextEventAt)

            val slot = requireNotNull(state.lastLootEquipmentSlot)
            val power = requireNotNull(state.lastLootEquipmentPower)
            val rarity = state.lastLootRarity
            val floor = engine.lootEquipmentPowerFloor(20L, rarity)
            assertTrue("Known rarity: $rarity", rarity in RARITIES)
            assertTrue("$slot/$rarity power=$power floor=$floor", power in floor..(floor + 11L))
            slots[slot] = slots.getValue(slot) + 1
            powerRolls[(power - floor).toInt()] = powerRolls.getValue((power - floor).toInt()) + 1
            rarities[rarity] = (rarities[rarity] ?: 0) + 1
            awards += 1
        }

        assertTrue("Bounded representative award sample: $awards", awards in 350..550)
        assertEquals(awards, slots.values.sum())
        assertEquals(awards, powerRolls.values.sum())
        slots.forEach { (slot, count) ->
            val share = count.toDouble() / awards
            // Wide, deterministic regression guard: the original defect gives three slots zero
            // and the remaining slots about one third each. Do not fit a production balance target.
            assertTrue("$slot count=$count/$awards; slots=$slots", share in 0.10..0.24)
        }
        powerRolls.forEach { (roll, count) ->
            assertTrue("Power roll $roll count=$count/$awards; rolls=$powerRolls",
                count.toDouble() / awards in 0.035..0.14)
        }
        println("Conditional adventure equipment awards=$awards slots=$slots rarities=$rarities powerRolls=$powerRolls")
    }

    @Test
    fun `event trophy rarity starts at uncommon and keeps legendary tiers rare`() {
        val counts = (0 until AdventureEventEngine.TROPHY_RARITY_ROLL_BOUND)
            .groupingBy(AdventureEventEngine::eventTrophyRarityForRoll)
            .eachCount()
        assertEquals(
            mapOf("고급" to 750, "희귀" to 200, "영웅" to 40, "전설" to 9, "신화" to 1),
            counts,
        )
        assertTrue("일반" !in counts)
    }

    @Test
    fun `equipment configured success uses the shared calibrated event equipment policy`() {
        val rewards = (0 until AdventureEventEngine.EQUIPMENT_REWARD_ROLL_BOUND).map {
            AdventureEventEngine.successfulItemReward(AdventureEventItemReward.EQUIPMENT, it)
        }
        assertEquals(AdventureEventEngine.EQUIPMENT_REWARD_ACCEPTED_ROLLS,
            rewards.count { it == AdventureEventItemReward.EQUIPMENT })
        assertEquals(
            AdventureEventEngine.EQUIPMENT_REWARD_ROLL_BOUND - AdventureEventEngine.EQUIPMENT_REWARD_ACCEPTED_ROLLS,
            rewards.count { it == AdventureEventItemReward.TROPHY },
        )
        assertEquals(
            AdventureEventItemReward.TROPHY,
            AdventureEventEngine.successfulItemReward(AdventureEventItemReward.TROPHY, 0),
        )
    }

    @Test
    fun `every successful event shares nine percent equipment chance and worse battle outcomes stay lower`() {
        AdventureEventEngine.all.forEach { definition ->
            val equipmentAwards = (0 until AdventureEventEngine.EQUIPMENT_REWARD_ROLL_BOUND).count { roll ->
                val selected = AdventureEventEngine.rewardKindForRoll(definition, roll % 100)
                AdventureEventEngine.rewardKindForOutcome(
                    selected,
                    AdventureEventOutcome.SUCCESS,
                    roll,
                ) == AdventureEventRewardKind.ITEM &&
                    AdventureEventEngine.successfulItemReward(AdventureEventItemReward.EQUIPMENT, roll) ==
                    AdventureEventItemReward.EQUIPMENT
            }
            assertEquals(definition.id, AdventureEventEngine.EQUIPMENT_REWARD_ACCEPTED_ROLLS, equipmentAwards)
        }

        MonsterGrade.entries.filter { it != MonsterGrade.NORMAL }.forEach { grade ->
            assertEquals(
                AdventureEventEngine.EQUIPMENT_REWARD_ACCEPTED_ROLLS,
                (0 until AdventureEventEngine.EVENT_BATTLE_REWARD_ROLL_BOUND).count {
                    AdventureEventEngine.battleEquipmentRewardForRoll(AdventureEventOutcome.SUCCESS, grade, it)
                },
            )
            assertEquals(
                AdventureEventEngine.PARTIAL_BATTLE_EQUIPMENT_REWARD_BASIS_POINTS,
                (0 until AdventureEventEngine.EVENT_BATTLE_REWARD_ROLL_BOUND).count {
                    AdventureEventEngine.battleEquipmentRewardForRoll(AdventureEventOutcome.PARTIAL, grade, it)
                },
            )
            assertEquals(
                AdventureEventEngine.FAILURE_BATTLE_EQUIPMENT_REWARD_BASIS_POINTS,
                (0 until AdventureEventEngine.EVENT_BATTLE_REWARD_ROLL_BOUND).count {
                    AdventureEventEngine.battleEquipmentRewardForRoll(AdventureEventOutcome.FAILURE, grade, it)
                },
            )
        }
    }

    @Test
    fun `success equipment may reach plus five while failed event battle equipment stops at plus four`() {
        val engine = SimpleGameEngine(enableAdventureEvents = true)
        assertEquals(5, rarityRank(engine.equipmentLootRarityForRoll(0)))
        assertEquals(5, rarityRank(engine.cappedEquipmentLootRarityForRoll(0, 5)))
        assertEquals(4, rarityRank(engine.cappedEquipmentLootRarityForRoll(0, 4)))
        assertEquals(4, rarityRank(engine.cappedEquipmentLootRarityForRoll(49, 4)))
        assertEquals(4, rarityRank(engine.cappedEquipmentLootRarityForRoll(50, 4)))
        assertTrue((0 until 1_000_000 step 997).all {
            rarityRank(engine.cappedEquipmentLootRarityForRoll(it, 4)) <= 4
        })
    }

    @Test
    fun `selected rewards consume only their required detail roll`() {
        val seed = 0x1357_2468_ACE0_BDF1L
        val base = AdventureEventRun(
            sequence = 1L,
            eventId = "bridge",
            approachId = "brace",
            startedAt = 0L,
            durationMillis = AdventureEventEngine.ACTION_MILLIS,
            heroLevel = 10L,
            primaryStat = AdventureEventStat.STR,
            secondaryStat = AdventureEventStat.CON,
            primaryValue = 10L,
            secondaryValue = 10L,
            successBasisPoints = 4_500,
            partialBasisPoints = 2_500,
            roll = 9_999,
            outcome = AdventureEventOutcome.FAILURE,
            experienceReward = 100L,
            goldReward = 0L,
            itemReward = AdventureEventItemReward.NONE,
            routeDelayMillis = 2_000L,
            rewardSeed = 1L,
            baseExperienceBudget = 100L,
            rewardKind = AdventureEventRewardKind.ROUTE,
        )

        val route = AdventureEventEngine.withOutcome(base, AdventureEventOutcome.SUCCESS, seed)
        assertEquals(AdventureEventItemReward.NONE, route.itemReward)
        assertEquals(AdventureEventEngine.forkRewardSeed(nextState(nextState(seed))), route.rewardSeed)

        val equipmentCandidate = AdventureEventEngine.withOutcome(
            base.copy(
                eventId = "ruins",
                approachId = "decode",
                rewardKind = AdventureEventRewardKind.ITEM,
            ),
            AdventureEventOutcome.SUCCESS,
            seed,
        )
        assertEquals(AdventureEventEngine.forkRewardSeed(nextState(seed)), equipmentCandidate.rewardSeed)
    }

    @Test
    fun `reward fork is deterministic and begin persists raw event state for scheduling`() {
        val engine = SimpleGameEngine(enableAdventureEvents = true)
        val state = engine.newGame("분기 검증", HeroClass.MAGE,
            engine.rollStats(131L, HeroClass.MAGE).stats, 503L, 0L)
        val combatSeed = state.rngState
        val run = AdventureEventEngine.begin(state, 1L)
        val rawEventState = state.adventureJourney.rngState
        assertEquals(AdventureEventEngine.forkRewardSeed(rawEventState), run.rewardSeed)
        assertEquals(run.rewardSeed, AdventureEventEngine.forkRewardSeed(rawEventState))
        assertEquals(combatSeed, state.rngState)
        AdventureEventEngine.scheduleNext(state.adventureJourney, 2L)
        assertEquals(nextState(nextState(rawEventState)), state.adventureJourney.rngState)
        assertEquals(run, state.adventureJourney.pending)
    }

    // Reproduce only the existing LCG boundary used to select eligible award states. This is
    // deliberately not a second implementation of the new avalanche or equipment distribution.
    private fun nextState(state: Long): Long =
        state * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L

    private fun bounded(state: Long, bound: Int): Int = ((state ushr 1) % bound.toLong()).toInt()

    private fun rarityRank(rarity: String): Int = when (rarity) {
        "신화" -> 5
        "전설" -> 4
        "영웅" -> 3
        "희귀" -> 2
        "고급" -> 1
        else -> 0
    }

    companion object {
        private val RARITIES = setOf("일반", "고급", "희귀", "영웅", "전설", "신화")
    }
}
