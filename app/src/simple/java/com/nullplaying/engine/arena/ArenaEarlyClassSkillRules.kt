package com.nullplaying.engine.arena

import com.nullplaying.model.HeroClass
import kotlin.math.roundToInt

/** Level 25/30 identities, resolved before issuance. Historical snapshots stay unchanged. */
internal object ArenaEarlyClassSkillRules {
    private fun ArenaIdentityDefinition.effect(
        effect: String, from: Double, to: Double = from, scale: Double = 1.0,
        lasting: Int = duration,
    ) = copy(
        kind = effect,
        magnitude = List(10) { from + (to - from) * it / 9.0 },
        damage = damage.map { (it * scale).roundToInt() },
        duration = lasting,
        options = options + ("effect_kind" to effect),
        numbers = numbers + mapOf(
            "effect_r1" to List(10) { from }, "effect_r10" to List(10) { to },
            "effect_duration_turns" to List(10) { lasting.toDouble() },
        ),
    )

    fun apply(d: ArenaIdentityDefinition): ArenaIdentityDefinition = when {
        // A separate frozen kind preserves the basic-only behavior in existing battle replays.
        d.heroClass == HeroClass.RANGER && d.slot == "S01" -> d.copy(
            kind = "VIGILANCE",
            duration = 6, cooldown = 8, charges = List(10) { 4 },
            magnitude = List(10) { 15.0 + 5.0 * it / 9.0 },
            weights = listOf(0.0,0.0,.6,0.0,.4,0.0),
            numbers = d.numbers + mapOf("magnitude_r1" to List(10) { 15.0 },
                "magnitude_r10" to List(10) { 20.0 }, "duration_turns" to List(10) { 6.0 },
                "cooldown_turns" to List(10) { 8.0 }, "charges" to List(10) { 4.0 }),
            options = d.options + mapOf("effect_kind" to "VIGILANCE", "stat_scaled" to "false",
                "role" to "기민한 회피", "unit" to "percent_separate_evasion"),
        )
        // Entry attacks trade a small amount of raw damage for each class's follow-through.
        d.heroClass == HeroClass.WARRIOR && d.slot in setOf("A01", "A02", "A03") ->
            d.copy(damage = d.damage.map { (it * .98).roundToInt() })
        d.heroClass == HeroClass.WARRIOR && d.slot == "A04" ->
            d.copy(damage = d.damage.map { (it * 1.04).roundToInt() })
        d.heroClass == HeroClass.RANGER && d.slot == "A03" ->
            d.copy(damage = d.damage.map { (it * .98).roundToInt() })
        d.heroClass == HeroClass.RANGER && d.slot == "A02" ->
            d.copy(damage = d.damage.map { (it * 1.05).roundToInt() })
        d.heroClass == HeroClass.ROGUE && d.slot == "A06" ->
            d.effect("poison_bonus", 10.0, 20.0, scale = .92)
        d.heroClass == HeroClass.ROGUE && d.slot == "A07" ->
            d.effect("opening_bonus", 10.0, 20.0)
        d.heroClass == HeroClass.RANGER && d.slot == "A06" ->
            d.effect("evasion_reduction", 4.0, 8.0, scale = .96, lasting = 2)
        d.heroClass == HeroClass.MAGE && d.slot == "A01" ->
            d.copy(damage = d.damage.map { (it * .985).roundToInt() })
        d.heroClass == HeroClass.MAGE && d.slot == "A06" ->
            d.copy(damage = d.damage.map { (it * 1.03).roundToInt() })
        d.heroClass == HeroClass.MAGE && d.slot == "A07" ->
            d.effect("burn_release", 75.0, 95.0, scale = .96)
        // Reliable accuracy trades a little raw damage for consistency at higher levels.
        d.heroClass == HeroClass.MAGE && d.slot == "A12" ->
            d.copy(damage = d.damage.map { (it * .97).roundToInt() })
        d.heroClass == HeroClass.CLERIC && d.slot == "A02" ->
            d.copy(damage = d.damage.map { (it * .98).roundToInt() })
        d.heroClass == HeroClass.CLERIC && d.slot == "A06" ->
            d.effect("heal_on_hit_hp", 1.0, 2.0, scale = .86)
        d.heroClass == HeroClass.CLERIC && d.slot == "A07" ->
            d.effect("self_cleanse", 0.0, scale = .92)
        d.heroClass == HeroClass.PALADIN && d.slot == "A06" ->
            d.effect("guarded_bonus", 10.0, 20.0, scale = .98)
        d.heroClass == HeroClass.PALADIN && d.slot == "A07" ->
            d.effect("recent_support_bonus", 10.0, 20.0)
        else -> ArenaLateClassSkillRules.apply(d)
    }
}
