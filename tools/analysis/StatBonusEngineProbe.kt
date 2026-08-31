package com.nullplaying.engine

import com.nullplaying.model.*
import java.io.File
import java.util.Random

/** Standalone pure-engine probe; no Android, Room, Firebase, Supabase or device code. */
object StatBonusEngineProbe {
    private val levels = setOf(1L, 5L, 10L, 20L, 30L, 40L, 50L, 60L, 70L, 80L, 90L, 100L)

    @JvmStatic
    fun main(args: Array<String>) {
        verifyBankInvariants()
        val samples = args.getOrNull(0)?.toInt() ?: 1
        val targetLevel = args.getOrNull(1)?.toLong() ?: 20L
        val output = File(args.getOrNull(2) ?: error("Explicit output CSV required"))
        val seedOffset = args.getOrNull(3)?.toInt() ?: 0
        BalanceSimulationHooks.configure(args.getOrNull(4) ?: "")
        val dense = args.getOrNull(5) == "dense"
        val classFilter = args.getOrNull(6).orEmpty().split(',').filter { it.isNotBlank() }.toSet()
        check(classFilter.all { name -> HeroClass.entries.any { it.name == name } })
        val engine = SimpleGameEngine(OfflineAdventureConfig(480, 12))
        output.bufferedWriter().use { writer ->
            writer.appendLine("class,seed_index,level,str,con,dex,int,wis,cha,hp,mp,tales,kills,seconds,bag,proc_pct,skill_damage_pct,skills,skill_uses,stat_power,equipment_power,sale_gold,items_sold,attacks,casts,experience")
            HeroClass.entries.filter { classFilter.isEmpty() || it.name in classFilter }.forEach { heroClass ->
                repeat(samples) { sample ->
                    val index = sample + seedOffset
                    BalanceSimulationHooks.resetCounters()
                    val seed = Random(20260831L + index * 1009L).nextLong()
                    val stats = engine.rollStats(seed, heroClass).stats
                    val state = engine.newGame("LOCAL_SIM", heroClass, stats, seed xor 721739L, 0L)
                    var recordedLevel = 0L
                    var events = 0L
                    while (true) {
                        if ((dense || state.hero.level in levels) && state.hero.level != recordedLevel) {
                            val h = state.hero
                            val s = h.stats
                            val weights = engine.skillSelectionWeights(state)
                            val skillDamage = h.stats.let {
                                state.skills.mapIndexed { skillIndex, skill ->
                                    val def = SkillCatalog.find(skill.catalogId)!!
                                    ((def.damagePercentMin + def.damagePercentMax) / 2.0 +
                                        skill.copy(usageCount = skill.nextUsageCount).damageBonusPercent) * weights[skillIndex]
                                }.sum() / weights.sum().coerceAtLeast(1)
                            }
                            writer.appendLine(listOf(heroClass.name, index, h.level,
                                s.strength, s.constitution, s.dexterity, s.intelligence, s.wisdom,
                                s.charisma, s.maxHealth, s.maxMana, state.totalTales,
                                state.totalKills, state.lastSettledAt / 1000.0, state.inventoryCapacity(),
                                engine.skillProcPercent(state), skillDamage, state.skills.size,
                                state.skills.sumOf { it.usageCount }, engine.characterStatPower(state),
                                engine.averageEquipmentPower(state), state.totalSaleGold,
                                state.totalItemsSold, BalanceSimulationHooks.attacks,
                                BalanceSimulationHooks.casts, h.experience).joinToString(","))
                            writer.flush()
                            recordedLevel = h.level
                        }
                        if (state.hero.level >= targetLevel) break
                        check(++events <= 8_000_000L) { "Bounded run exceeded event budget" }
                        // A one-minute batch replays every underlying event in the real
                        // engine but avoids re-running migration checks on every attack.
                        engine.settle(state, state.lastSettledAt + 60_000L)
                    }
                    System.err.println("probe ${heroClass.name} seed=$index level=${state.hero.level} kills=${state.totalKills} events=$events")
                }
            }
        }
    }

    private fun verifyBankInvariants() {
        for (capacity in listOf(480L, 540L, 600L)) {
            val engine = SimpleGameEngine(OfflineAdventureConfig(capacity, 12))
            val stats = engine.rollStats(812731L, HeroClass.WARRIOR).stats
            val state = engine.newGame("LOCAL_BANK_CHECK", HeroClass.WARRIOR, stats, 918231L, 0L)
            state.offlineAdventureMillis = 0L
            engine.advanceOfflineAdventureForeground(state, 60_000L)
            check(state.offlineAdventureMillis == capacity * 60_000L / 12)
            engine.advanceOfflineAdventureForeground(state, 11 * 60_000L)
            check(state.offlineAdventureMillis == capacity * 60_000L)
            engine.settleOfflineWithOfflineAdventure(state, 24 * 3_600_000L)
            check(state.offlineAdventureMillis == 0L)
            check(state.lastSettledAt == 24 * 3_600_000L)
            val kills = state.totalKills
            engine.settleOfflineWithOfflineAdventure(state, 48 * 3_600_000L)
            check(state.totalKills == kills) // Empty bank cannot retroactively farm.
        }
        System.err.println("bank checks: 480/540/600m, 1m/12m charge, 24h drain, empty-bank pause PASS")
    }
}
