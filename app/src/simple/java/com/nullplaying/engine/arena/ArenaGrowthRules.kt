package com.nullplaying.engine.arena

import com.nullplaying.engine.arena.ArenaGrowthEvent.*
import com.nullplaying.engine.arena.ArenaGrowthPhase.*
import com.nullplaying.engine.arena.ArenaGrowthTarget.ANY_ATTACK
import com.nullplaying.engine.arena.ArenaGrowthTarget.BASIC
import com.nullplaying.engine.arena.ArenaGrowthTarget.SKILL
import com.nullplaying.engine.arena.ArenaGrowthTarget.ONE_TURN_ATTACK
import com.nullplaying.engine.arena.ArenaGrowthTarget.ONE_TURN_SKILL
import com.nullplaying.engine.arena.ArenaGrowthTarget.TWO_TURN_SKILL
import com.nullplaying.engine.arena.ArenaGrowthTarget.LONG_SKILL
import com.nullplaying.engine.arena.ArenaGrowthTarget.ANY_SUPPORT
import com.nullplaying.engine.arena.ArenaGrowthTarget.LINKED_SUPPORT
import com.nullplaying.engine.arena.ArenaGrowthTarget.INCOMING_BASIC
import com.nullplaying.engine.arena.ArenaGrowthTarget.INCOMING_SKILL

/** Conditions and localized descriptions share one entry; support/engine constants supply all values. */
internal object ArenaGrowthRules {
    private fun s(cls: String, n: Int) = "ARENA_SUP_${cls}_${n.toString().padStart(2, '0')}"
    private fun w(n: Int) = s("FIGHTER", n)
    private fun r(n: Int) = s("ROGUE", n)
    private fun a(n: Int) = s("RANGER", n)
    private fun m(n: Int) = s("MAGE", n)
    private fun c(n: Int) = s("CLERIC", n)
    private fun p(n: Int) = s("PALADIN", n)
    private fun rule(id: String, phase: ArenaGrowthPhase, target: ArenaGrowthTarget, en: String, ja: String,
        event: ArenaGrowthEvent = NONE, support: String? = null,
        condition: (ArenaGrowthContext, ArenaGrowthHistory) -> Boolean) =
        "AT9_$id" to ArenaGrowthRule(phase, target, event, support, en, ja, condition)

    val rules: Map<String, ArenaGrowthRule> = listOf(
        rule("WARRIOR_A01", COST, SKILL, "After a basic attack deals HP damage.", "通常攻撃でHPダメージを与えた後。", OWN_HIT) { x, _ -> x.isBasic && x.hpDamage > 0 },
        rule("WARRIOR_A02", DAMAGE, BASIC, "After two different consecutive direct attacks both deal HP damage.", "異なる直接攻撃が連続で2回HPダメージを与えた後。", OWN_HIT) { x, h -> x.hpDamage > 0 && h.ownHits.lastOrNull()?.let { it.hpDamage > 0 && it.actionId != x.actionId } == true },
        rule("WARRIOR_A03", DAMAGE, BASIC, "While Battle Cry has at least 2 full turns remaining.", "戦いの雄叫びの残り期間が2ターン以上の間。") { x, _ -> x.active(w(2)) && (x.supportRemainingTurns[w(2)] ?: 0) >= 2 },
        rule("WARRIOR_A04", ACCURACY, TWO_TURN_SKILL, "After Ironwall Stance expires naturally while HP is above 50%.", "鉄壁の構えが自然終了し、HPが50%を超えている時。", SUPPORT_EXPIRED) { x, _ -> x.causedBy(w(1)) && x.selfHpRatio > .5 },
        rule("WARRIOR_A05", DAMAGE, BASIC, "At basic attack completion while MP is at most 25%.", "通常攻撃の完了時、MPが25%以下なら。") { x, _ -> x.selfMpRatio <= .25 },
        rule("WARRIOR_A06", ACCURACY, ANY_ATTACK, "After Steady Breathing removes an accuracy penalty.", "「呼吸を整える」で命中低下を実際に解除した後。", CLEANSED) { x, _ -> x.causedBy(w(6)) && x.removedStatusKind == "accuracy" },
        rule("WARRIOR_A07", DAMAGE, ONE_TURN_SKILL, "Begin during Battle Cry after observing an enemy long attack with 1 turn left; finish before Battle Cry expires.", "戦いの雄叫び中、敵の長い攻撃の残り1ターンを確認して開始し、戦いの雄叫び中に完了。") { x, _ -> x.active(w(2)) && x.observedLongCast && x.startTargetCastRemaining == 1 },
        rule("WARRIOR_B01", SHIELD, BASIC, "When a basic attack reaches an enemy shield.", "通常攻撃が敵の残っているシールドに当たる時。") { x, _ -> x.targetShield > 0 },
        rule("WARRIOR_B02", DAMAGE, ANY_ATTACK, "After a landed attack is fully absorbed by a shield.", "命中した攻撃がシールドに全て吸収された後。", ENEMY_SHIELD_ABSORBED) { x, _ -> x.absorbed > 0 && x.hpDamage == 0.0 },
        rule("WARRIOR_B03", COST, ANY_SUPPORT, "After your direct attack breaks an enemy shield.", "自分の直接攻撃で敵のシールドを破壊した後。", SHIELD_BROKEN) { x, _ -> x.absorbed > 0 },
        rule("WARRIOR_B04", REDUCTION, ANY_ATTACK, "While Ironwall Stance is active and the enemy has a shield.", "鉄壁の構えが有効で、敵にもシールドがある間。") { x, _ -> x.active(w(1)) && x.targetShield > 0 },
        rule("WARRIOR_B05", SUPPORT_POWER, ArenaGrowthTarget.SUPPORT_POWER, "When Shatter Preparation is consumed against a shield.", "シールドに対して破砕の構えを消費する時。", support = w(3)) { x, _ -> x.consumed(w(3)) && x.targetShield > 0 },
        rule("WARRIOR_B06", SHIELD, ANY_ATTACK, "After Ironwall Stance expires naturally while the enemy still has a shield.", "鉄壁の構えが自然終了し、敵にシールドが残っている時。", SUPPORT_EXPIRED) { x, _ -> x.causedBy(w(1)) && x.targetShield > 0 },
        rule("WARRIOR_B07", DAMAGE, ONE_TURN_SKILL, "While the enemy has support-based direct damage reduction.", "敵に補助スキルによる直接ダメージ軽減がある間。") { x, _ -> x.targetHasDamageReduction },
        rule("WARRIOR_C01", DAMAGE, BASIC, "After surviving direct HP damage.", "直接HPダメージを受けて生き残った後。", TAKEN_HIT) { x, _ -> x.hpDamage > 0 },
        rule("WARRIOR_C02", COST, LINKED_SUPPORT, "Begin Ironwall Stance at HP ≤35% while an enemy direct attack has at least 2 turns left.", "HP35%以下、敵の直接攻撃の残り2ターン以上で鉄壁の構えを開始。", support = w(1)) { x, _ -> x.selfHpRatio <= .35 && x.targetCastRemaining >= 2 && !x.targetCastingSupport },
        rule("WARRIOR_C03", ACCURACY, ANY_ATTACK, "After Ironwall Stance actually reduces direct HP damage.", "鉄壁の構えで直接HPダメージを実際に軽減した後。", DAMAGE_REDUCED) { x, _ -> x.causedBy(w(1)) && x.reduced > 0 },
        rule("WARRIOR_C04", DAMAGE, TWO_TURN_SKILL, "After two consecutive enemy basic attacks both deal HP damage.", "敵の通常攻撃が連続で2回HPダメージを与えた後。", TAKEN_HIT) { x, h -> x.isBasic && x.hpDamage > 0 && h.takenHits.lastOrNull()?.let { it.isBasic && it.hpDamage > 0 } == true },
        rule("WARRIOR_C05", DAMAGE, BASIC, "At turn end after both fighters dealt direct HP damage in that turn.", "同じターンに双方が直接HPダメージを与え、ターン終了まで生存。", TURN_END) { x, h -> h.ownHits.any { it.turn == x.turn && it.hpDamage > 0 } && h.takenHits.any { it.turn == x.turn && it.hpDamage > 0 } },
        rule("WARRIOR_C06", COST, LINKED_SUPPORT, "After a basic attack consumes Counter Preparation and deals HP damage.", "反撃の構えを消費した通常攻撃でHPダメージを与えた後。", OWN_HIT, w(1)) { x, _ -> x.isBasic && x.hpDamage > 0 && x.consumed(w(4)) },
        rule("WARRIOR_C07", ACCURACY, BASIC, "After Emergency Bandage restores actual HP.", "応急手当でHPを実際に回復した後。", HEALED) { x, _ -> x.causedBy(w(8)) && x.healing > 0 },

        rule("ROGUE_A01", ACCURACY, ANY_ATTACK, "After Footwork successfully evades a direct attack; ordinary misses do not count.", "身のこなしで直接攻撃の回避に成功した後。通常のミスは対象外。", EVADED) { x, _ -> x.causedBy(r(4)) },
        rule("ROGUE_A02", DAMAGE, ONE_TURN_SKILL, "When an originally 1-turn attack consumes Stealth on a successful hit.", "元が1ターンの攻撃スキルが命中し、隠密を消費する時。") { x, _ -> x.active(r(1)) || x.consumed(r(1)) },
        rule("ROGUE_A03", COST, SKILL, "After your direct attack fails its ordinary hit check.", "自分の直接攻撃が通常の命中判定で外れた後。", OWN_MISS) { _, _ -> true },
        rule("ROGUE_A04", COST, LINKED_SUPPORT, "After a direct attack consumes Stealth and deals HP damage.", "隠密を消費した直接攻撃でHPダメージを与えた後。", OWN_HIT, r(4)) { x, _ -> x.hpDamage > 0 && x.consumed(r(1)) },
        rule("ROGUE_A05", DAMAGE, ONE_TURN_ATTACK, "After an enemy direct attack fails its ordinary hit check.", "敵の直接攻撃が通常の命中判定で外れた後。", ENEMY_MISS) { _, _ -> true },
        rule("ROGUE_A06", DAMAGE, BASIC, "While your Smoke Screen accuracy penalty remains on the enemy.", "自分の煙幕による命中低下が敵に残っている間。") { x, _ -> r(2) in x.targetSupports },
        rule("ROGUE_A07", COST, LINKED_SUPPORT, "After a basic attack consumes Seize Opportunity and deals HP damage.", "「好機をつかむ」を消費した通常攻撃でHPダメージを与えた後。", OWN_HIT, r(1)) { x, _ -> x.isBasic && x.hpDamage > 0 && x.consumed(r(5)) },
        rule("ROGUE_B01", DAMAGE, BASIC, "After two basic HP hits within 4 turns, with no ordinary miss between them.", "4ターン以内に通常攻撃で2回HPに命中し、その間に通常のミスがない時。", OWN_HIT) { x, h -> x.isBasic && x.hpDamage > 0 && h.basicHits.lastOrNull()?.let { x.turn - it.turn <= 4 && h.lastMissTurn < it.turn } == true },
        rule("ROGUE_B02", DAMAGE, LONG_SKILL, "After Footwork succeeds while you are casting an originally 2–3-turn attack; applies only to that cast.", "元が2～3ターンの攻撃を詠唱中に身のこなしが成功した時。その詠唱だけに適用。", EVADED) { x, _ -> x.causedBy(r(4)) && x.selfCastOriginalTurns in 2..3 },
        rule("ROGUE_B03", COST, ONE_TURN_SKILL, "Begin at HP ≤50% while enemy HP is at least 50%.", "自分のHP50%以下、敵のHP50%以上で開始。") { x, _ -> x.selfHpRatio <= .5 && x.targetHpRatio >= .5 },
        rule("ROGUE_B04", DAMAGE, BASIC, "Begin after observing the enemy casting healing or protection support.", "敵が回復または保護の補助スキルを詠唱中と確認して開始。") { x, _ -> x.startTargetCastingRecoveryOrProtection },
        rule("ROGUE_B05", ArenaGrowthPhase.DOT, ArenaGrowthTarget.DOT, "When Poison Coating actually adds a new poison layer; blocked or capped applications do not count.", "毒の塗布が新しい毒を実際に付与した時。阻止や上限到達は対象外。", support = r(11)) { x, _ -> x.causedBy(r(11)) },
        rule("ROGUE_B06", COST, LINKED_SUPPORT, "Begin with exactly 2 of your poison layers and at least 2 future poison ticks remaining.", "自分の毒がちょうど2層、今後の毒ダメージが2回以上残る時に開始。", support = r(12)) { x, _ -> x.ownPoisonLayers == 2 && x.ownPoisonFutureTicks >= 2 },
        rule("ROGUE_B07", ACCURACY, ANY_ATTACK, "After Escape Art removes poison or burn.", "脱出術で毒または火傷を実際に解除した後。", CLEANSED) { x, _ -> x.causedBy(r(7)) && x.removedStatusKind in setOf("poison", "burn") },
        rule("ROGUE_C01", DAMAGE, BASIC, "At completion, your MP percentage is at least 25 points above the enemy's.", "完了時、自分のMP割合が敵より25ポイント以上高い時。") { x, _ -> x.selfMpRatio - x.targetMpRatio >= .25 },
        rule("ROGUE_C02", COST, ONE_TURN_SKILL, "Begin while MP is at most 25%.", "MPが25%以下で開始。") { x, _ -> x.selfMpRatio <= .25 },
        rule("ROGUE_C03", DAMAGE, BASIC, "After observing the enemy pay at least 15 MP to start support.", "敵が補助スキルの開始で15MP以上を支払った後。", ENEMY_PAID) { x, _ -> x.isSupport && x.paidMp >= 15 },
        rule("ROGUE_C04", COST, SKILL, "After Footwork evades an originally 3-turn direct attack.", "元が3ターンの直接攻撃を身のこなしで回避した後。", EVADED) { x, _ -> x.causedBy(r(4)) && x.originalCastTurns == 3 },
        rule("ROGUE_C05", ACCURACY, BASIC, "After Stealth expires naturally with its charge unused.", "隠密を未使用のまま自然終了した後。", SUPPORT_EXPIRED) { x, _ -> x.causedBy(r(1)) && x.supportHadUnusedCharges },
        rule("ROGUE_C06", DAMAGE, ANY_ATTACK, "After Disruption Preparation removes at least 10 actual enemy MP.", "妨害準備で敵のMPを実際に10以上減らした後。", MANA_DRAINED) { x, _ -> x.causedBy(r(3)) && x.manaLost >= 10 },
        rule("ROGUE_C07", DAMAGE, BASIC, "While Wrist Check is active and the enemy cannot afford any owned paid attack at its current effective cost.", "手首牽制中、敵が所持する有料攻撃の実効MP費用を1つも払えない時。") { x, _ -> r(10) in x.targetSupports && x.targetCannotAffordOwnedSkill },

        rule("RANGER_A01", COST, LINKED_SUPPORT, "Begin Steady Aim after observing a 2–3-turn enemy attack with at least 2 turns left.", "元が2～3ターンの敵の攻撃の残り2ターン以上を確認し、精密照準を開始。", support = a(1)) { x, _ -> x.targetLongCast && x.targetCastRemaining >= 2 },
        rule("RANGER_A02", DAMAGE, BASIC, "At completion, the enemy is still casting an originally 2–3-turn attack.", "完了時、敵が元2～3ターンの攻撃をまだ詠唱中なら。") { x, _ -> x.targetLongCast && x.targetCastRemaining > 0 },
        rule("RANGER_A03", ACCURACY, ONE_TURN_SKILL, "Begin after observing the enemy already casting an attack skill.", "敵がすでに攻撃スキルを詠唱中と確認して開始。") { x, _ -> !x.startTargetCastingSupport && x.startTargetCastOriginalTurns > 0 },
        rule("RANGER_A04", COST, LINKED_SUPPORT, "Begin Vigilance while enemy MP is at most 20%.", "敵のMP20%以下で警戒態勢を開始。", support = a(6)) { x, _ -> x.targetMpRatio <= .2 },
        rule("RANGER_A05", COST, SKILL, "After a basic attack deals HP damage to an enemy still casting a long attack.", "長い攻撃を詠唱中の敵へ通常攻撃でHPダメージを与えた後。", OWN_HIT) { x, _ -> x.isBasic && x.hpDamage > 0 && x.targetLongCast && x.targetCastRemaining > 0 },
        rule("RANGER_A06", SUPPORT_POWER, ArenaGrowthTarget.SUPPORT_POWER, "While Rapid Shot Preparation is active and the enemy is still casting a long attack.", "速射準備中、敵が長い攻撃をまだ詠唱中なら。", support = a(3)) { x, _ -> x.isBasic && (x.active(a(3)) || x.consumed(a(3))) && x.targetLongCast && x.targetCastRemaining > 0 },
        rule("RANGER_A07", ACCURACY, SKILL, "After Shock Trap is consumed but resistance or control immunity prevents its delay.", "衝撃の罠を消費したが、抵抗や制御耐性で遅延が阻まれた後。", CONTROL_RESISTED) { x, _ -> x.causedBy(a(11)) },
        rule("RANGER_B01", SHIELD, BASIC, "After your direct attack is actually absorbed by an enemy shield.", "自分の直接攻撃が敵のシールドに実際に吸収された後。", ENEMY_SHIELD_ABSORBED) { x, _ -> x.absorbed > 0 },
        rule("RANGER_B02", DAMAGE, BASIC, "While Steady Aim is active and enemy HP is at least 80%.", "精密照準が有効で、敵のHPが80%以上なら。") { x, _ -> x.active(a(1)) && x.targetHpRatio >= .8 },
        rule("RANGER_B03", COST, LINKED_SUPPORT, "After your direct attack fails its ordinary hit check.", "自分の直接攻撃が通常の命中判定で外れた後。", OWN_MISS, a(1)) { _, _ -> true },
        rule("RANGER_B04", REDUCTION, INCOMING_BASIC, "While Vigilance is active and HP is at most 35%.", "警戒態勢が有効で、HPが35%以下なら。") { x, _ -> x.active(a(6)) && x.selfHpRatio <= .35 },
        rule("RANGER_B05", COST, SKILL, "After Expose actually removes enemy Stealth or all Mirror Images.", "看破で敵の隠密または分身を全て実際に解除した後。", DISPELLED) { x, _ -> x.causedBy(a(2)) && x.removedStatusKind in setOf("stealth", "mirror") },
        rule("RANGER_B06", SUPPORT_POWER, ArenaGrowthTarget.SUPPORT_POWER, "When Barrier Hunt is consumed against a shield.", "シールドに対して防壁狩りを消費する時。", support = a(8)) { x, _ -> x.consumed(a(8)) && x.targetShield > 0 },
        rule("RANGER_B07", DAMAGE, ANY_ATTACK, "While Observe Weakness is active against actual support damage reduction.", "弱点観察中、敵に実際の補助ダメージ軽減がある時。") { x, _ -> x.active(a(5)) && x.targetHasDamageReduction },
        rule("RANGER_C01", DAMAGE, BASIC, "After observing the enemy restore at least 1 actual HP.", "敵がHPを実際に1以上回復した後。", ENEMY_HEALED) { x, _ -> x.healing >= 1 },
        rule("RANGER_C02", DAMAGE, BASIC, "Your HP is at most 50%, enemy HP at least 80%, and enemy support reduction applies.", "自分のHP50%以下、敵のHP80%以上で、敵の補助軽減が適用される時。") { x, _ -> x.selfHpRatio <= .5 && x.targetHpRatio >= .8 && x.targetHasDamageReduction },
        rule("RANGER_C03", COST, LINKED_SUPPORT, "Begin Vigilance while HP is at most 30%.", "HP30%以下で警戒態勢を開始。", support = a(6)) { x, _ -> x.selfHpRatio <= .3 },
        rule("RANGER_C04", DAMAGE, SKILL, "While Vigilance is active and enemy MP is at most 20%.", "警戒態勢が有効で、敵のMPが20%以下なら。") { x, _ -> x.active(a(6)) && x.targetMpRatio <= .2 },
        rule("RANGER_C05", COST, LINKED_SUPPORT, "Begin Track Recovery using an unused actual healing event worth more than 0 and at most 5% of enemy max HP.", "未使用の実回復が敵の最大HPの0%超～5%以下の時、回復追跡を開始。", support = a(10)) { x, _ -> x.capturedHealingMaxHpRatio > 0 && x.capturedHealingMaxHpRatio <= .05 },
        rule("RANGER_C06", COST, LINKED_SUPPORT, "After Track Recovery's extra damage actually reduces enemy HP.", "回復追跡の追加ダメージが敵のHPを実際に減らした後。", OWN_HIT, a(1)) { x, _ -> x.hpDamage > 0 && x.consumed(a(10)) },
        rule("RANGER_C07", SUPPORT_POWER, ArenaGrowthTarget.SUPPORT_POWER, "When Track Recovery converts captured actual healing into extra damage.", "回復追跡が記録した実回復量を追加ダメージに変換する時。", support = a(10)) { x, _ -> x.consumed(a(10)) },

        rule("MAGE_A01", COST, SKILL, "After Mana Shield absorbs at least 1 damage and you survive.", "魔力シールドが1以上のダメージを吸収して生存した後。", SHIELD_ABSORBED) { x, _ -> x.causedBy(m(1)) && x.absorbed >= 1 },
        rule("MAGE_A02", COST, LINKED_SUPPORT, "Begin Mana Focus with at least 50% MP before payment.", "支払い前のMPが50%以上で魔力凝縮を開始。", support = m(7)) { x, _ -> x.selfMpRatio >= .5 },
        rule("MAGE_A03", DAMAGE, LONG_SKILL, "Complete an originally 3-turn attack that began with at least 50% MP.", "開始前にMP50%以上だった、元3ターンの攻撃を完了。") { x, _ -> x.originalCastTurns == 3 && x.startSelfMpRatio >= .5 },
        rule("MAGE_A04", ACCURACY, SKILL, "After a basic attack deals actual HP damage.", "通常攻撃で実際にHPダメージを与えた後。", OWN_HIT) { x, _ -> x.isBasic && x.hpDamage > 0 },
        rule("MAGE_A05", DAMAGE, BASIC, "At completion while MP is at most 20%.", "完了時、MPが20%以下なら。") { x, _ -> x.selfMpRatio <= .2 },
        rule("MAGE_A06", SUPPORT_POWER, ArenaGrowthTarget.SUPPORT_POWER, "When Mana Focus is consumed by an owned attack skill.", "所持する攻撃スキルが魔力凝縮を消費する時。", support = m(7)) { x, _ -> x.skill && x.consumed(m(7)) },
        rule("MAGE_A07", COST, LINKED_SUPPORT, "Begin Spell Stabilization while HP is at most 35%.", "HP35%以下で呪文安定化を開始。", support = m(3)) { x, _ -> x.selfHpRatio <= .35 },
        rule("MAGE_B01", ACCURACY, SKILL, "After surviving direct HP damage.", "直接HPダメージを受けて生き残った後。", TAKEN_HIT) { x, _ -> x.hpDamage > 0 },
        rule("MAGE_B02", COST, SKILL, "After two different basic HP hits no more than 3 turns apart.", "3ターン以内に通常攻撃で2回HPに命中した後。", OWN_HIT) { x, h -> x.isBasic && x.hpDamage > 0 && h.basicHits.lastOrNull()?.let { x.turn - it.turn <= 3 } == true },
        rule("MAGE_B03", DAMAGE, BASIC, "After observing the enemy pay at least 8 MP to begin an action.", "敵が技の開始で8MP以上を支払った後。", ENEMY_PAID) { x, _ -> x.paidMp >= 8 },
        rule("MAGE_B04", REDUCTION, ANY_ATTACK, "While casting an originally 3-turn attack with MP at most 30%.", "元3ターンの攻撃を詠唱中で、MPが30%以下なら。") { x, _ -> x.selfCastOriginalTurns == 3 && x.selfMpRatio <= .3 },
        rule("MAGE_B05", DAMAGE, ANY_ATTACK, "While the enemy still has a burn applied by your Flame Inscription.", "自分の火炎刻印で付与した火傷が敵に残っている間。") { x, _ -> x.targetHasOwnBurn },
        rule("MAGE_B06", ArenaGrowthPhase.DOT, ArenaGrowthTarget.DOT, "When your Flame Inscription burn ticks against an enemy shield.", "自分の火炎刻印の火傷が、シールドを持つ敵にダメージを与える時。", support = m(11)) { x, _ -> x.targetShield > 0 },
        rule("MAGE_B07", COST, LINKED_SUPPORT, "Begin Ember Ward while HP is at most 35%.", "HP35%以下で残り火の結界を開始。", support = m(12)) { x, _ -> x.selfHpRatio <= .35 },
        rule("MAGE_C01", SHIELD, ArenaGrowthTarget.SHIELD, "Complete Mana Shield while HP is at most 35%.", "HP35%以下で魔力シールドを完了。", support = m(1)) { x, _ -> x.selfHpRatio <= .35 },
        rule("MAGE_C02", COST, LINKED_SUPPORT, "Begin Mana Shield with MP at most 30% before payment.", "支払い前のMP30%以下で魔力シールドを開始。", support = m(1)) { x, _ -> x.selfMpRatio <= .3 },
        rule("MAGE_C03", ACCURACY, BASIC, "After Mana Shield actually absorbs direct attack damage.", "魔力シールドが直接攻撃のダメージを実際に吸収した後。", SHIELD_ABSORBED) { x, _ -> x.causedBy(m(1)) && x.absorbed > 0 },
        rule("MAGE_C04", REDUCTION, INCOMING_BASIC, "While casting an owned attack and Mana Shield has absorption remaining.", "所持する攻撃を詠唱中で、魔力シールドに吸収量が残っている間。") { x, _ -> x.selfCastOriginalTurns > 0 && x.selfShield > 0 && x.active(m(1)) },
        rule("MAGE_C05", COST, SKILL, "After Mirror Image actually nullifies a direct attack.", "ミラーイメージが直接攻撃を実際に無効化した後。", MIRROR_BLOCKED) { x, _ -> x.causedBy(m(2)) },
        rule("MAGE_C06", SHIELD, ArenaGrowthTarget.SHIELD, "After Spell Ward actually blocks one new harmful status.", "呪文障壁が新たな有害状態を実際に1回防いだ後。", STATUS_BLOCKED, m(1)) { x, _ -> x.causedBy(m(8)) },
        rule("MAGE_C07", DAMAGE, BASIC, "After Countermagic actually dispels an enemy buff.", "逆魔法で敵の強化を実際に解除した後。", DISPELLED) { x, _ -> x.causedBy(m(5)) },

        rule("CLERIC_A01", HEAL, ArenaGrowthTarget.HEAL, "Complete Heal while alive and HP is at most 35%.", "生存し、HP35%以下で回復を完了。", support = c(1)) { x, _ -> x.selfHpRatio <= .35 },
        rule("CLERIC_A02", COST, SKILL, "After Heal restores at least 5% of your maximum HP.", "回復で最大HPの5%以上を実際に取り戻した後。", HEALED) { x, _ -> x.causedBy(c(1)) && x.healingMaxHpRatio >= .05 },
        rule("CLERIC_A03", COST, LINKED_SUPPORT, "Begin Heal after observing a 3-turn enemy attack with at least 2 turns left.", "元3ターンの敵の攻撃の残り2ターン以上を確認し、回復を開始。", support = c(1)) { x, _ -> x.targetCastOriginalTurns == 3 && x.targetCastRemaining >= 2 && !x.targetCastingSupport },
        rule("CLERIC_A04", REDUCTION, INCOMING_BASIC, "While casting Heal with Vow of Restraint active.", "節制の誓いが有効な間に回復を詠唱中なら。") { x, _ -> x.active(c(6)) && x.selfCastActionId == c(1) },
        rule("CLERIC_A05", COST, LINKED_SUPPORT, "Begin Heal with MP at most 30% before payment.", "支払い前のMP30%以下で回復を開始。", support = c(1)) { x, _ -> x.selfMpRatio <= .3 },
        rule("CLERIC_A06", HEAL, ArenaGrowthTarget.HEAL, "At a Prayer of Regeneration tick while missing at least 20% of maximum HP.", "再生の祈りの回復時、HP欠損が最大HPの20%以上なら。", support = c(4)) { x, _ -> x.selfHpRatio <= .8 },
        rule("CLERIC_A07", COST, LINKED_SUPPORT, "After two different ticks of one Prayer of Regeneration each restore actual HP.", "同じ再生の祈りの異なる2回の回復が、それぞれ実際にHPを回復した後。", HOT_HEALED, c(6)) { x, h -> x.causedBy(c(4)) && x.healing > 0 && (h.hotTicks[x.supportInstanceId] ?: 0) == 1 },
        rule("CLERIC_B01", ACCURACY, ANY_ATTACK, "After Heal restores at least 1 actual HP.", "回復でHPを実際に1以上取り戻した後。", HEALED) { x, _ -> x.causedBy(c(1)) && x.healing >= 1 },
        rule("CLERIC_B02", HEAL, ArenaGrowthTarget.HEAL, "After a basic attack deals actual HP damage.", "通常攻撃で実際にHPダメージを与えた後。", OWN_HIT, c(1)) { x, _ -> x.isBasic && x.hpDamage > 0 },
        rule("CLERIC_B03", DAMAGE, ANY_ATTACK, "While Vow of Restraint is active and HP is at most 35%.", "節制の誓いが有効で、HPが35%以下なら。") { x, _ -> x.active(c(6)) && x.selfHpRatio <= .35 },
        rule("CLERIC_B04", DAMAGE, BASIC, "At completion, MP is at most 20% and your HP percentage exceeds the enemy's.", "完了時、MP20%以下で自分のHP割合が敵より高い時。") { x, _ -> x.selfMpRatio <= .2 && x.selfHpRatio > x.targetHpRatio },
        rule("CLERIC_B05", COST, LINKED_SUPPORT, "After a direct attack deals HP damage while Blessing is active.", "祝福中の直接攻撃がHPダメージを与えた後。", OWN_HIT, c(1)) { x, _ -> x.hpDamage > 0 && x.active(c(2)) },
        rule("CLERIC_B06", SUPPORT_POWER, ArenaGrowthTarget.SUPPORT_POWER, "When Life Link converts actual direct HP damage into healing.", "命のつながりが実際の直接HPダメージを回復に変換する時。", support = c(9)) { x, _ -> x.active(c(9)) || x.consumed(c(9)) },
        rule("CLERIC_B07", DAMAGE, BASIC, "After Seal of Discipline actually removes one enemy buff.", "戒めの印が敵の強化を実際に1つ解除した後。", DISPELLED) { x, _ -> x.causedBy(c(10)) },
        rule("CLERIC_C01", ACCURACY, BASIC, "After Vow of Restraint actually reduces direct HP damage.", "節制の誓いが直接HPダメージを実際に軽減した後。", DAMAGE_REDUCED) { x, _ -> x.causedBy(c(6)) && x.reduced > 0 },
        rule("CLERIC_C02", COST, LINKED_SUPPORT, "Begin Vow of Restraint at HP ≤50% while enemy MP is at least 50%.", "自分のHP50%以下、敵のMP50%以上で節制の誓いを開始。", support = c(6)) { x, _ -> x.selfHpRatio <= .5 && x.targetMpRatio >= .5 },
        rule("CLERIC_C03", REDUCTION, LINKED_SUPPORT, "After your direct attack fails its ordinary hit check.", "自分の直接攻撃が通常の命中判定で外れた後。", OWN_MISS, c(6)) { _, _ -> true },
        rule("CLERIC_C04", DAMAGE, ANY_ATTACK, "After Purify actually removes a harmful status.", "浄化が有害状態を実際に解除した後。", CLEANSED) { x, _ -> x.causedBy(c(3)) },
        rule("CLERIC_C05", COST, LINKED_SUPPORT, "After Prayer of Sanctuary actually shortens a new harmful status by 1 turn.", "聖域の祈りが新たな有害状態の期間を実際に1ターン短縮した後。", STATUS_SHORTENED, c(1)) { x, _ -> x.causedBy(c(8)) },
        rule("CLERIC_C06", COST, LINKED_SUPPORT, "Begin Light of Truth against at least 2 remaining mirror opportunities and no Stealth.", "隠密がなく、分身の判定機会が2回以上残る敵へ真実の光を開始。", support = c(7)) { x, _ -> x.targetMirrorOpportunities >= 2 && !x.targetHasStealth },
        rule("CLERIC_C07", COST, LINKED_SUPPORT, "After Grace against Impact actually reduces direct HP damage.", "一撃の加護が直接HPダメージを実際に軽減した後。", DAMAGE_REDUCED, c(1)) { x, _ -> x.causedBy(c(5)) && x.reduced > 0 },

        rule("PALADIN_A01", DAMAGE, ANY_ATTACK, "After Guardian's Vow actually reduces enemy attack-skill HP damage.", "守護の誓約が敵の攻撃スキルによるHPダメージを実際に軽減した後。", DAMAGE_REDUCED) { x, _ -> x.causedBy(p(1)) && x.skill && x.reduced > 0 },
        rule("PALADIN_A02", COST, LONG_SKILL, "Begin an originally 2–3-turn attack while Sacred Focus still has a charge.", "聖なる集中の使用権が残る間に、元2～3ターンの攻撃を開始。") { x, _ -> x.active(p(9)) },
        rule("PALADIN_A03", DAMAGE, BASIC, "Begin after observing an originally 2–3-turn enemy attack with at least 2 turns left.", "元2～3ターンの敵の攻撃の残り2ターン以上を確認して開始。") { x, _ -> x.observedLongCast && x.startTargetCastRemaining >= 2 },
        rule("PALADIN_A04", COST, LINKED_SUPPORT, "After surviving HP damage from a paid enemy attack skill.", "敵の有料攻撃スキルのHPダメージを受けて生き残った後。", TAKEN_HIT, p(1)) { x, _ -> x.skill && x.hpDamage > 0 },
        rule("PALADIN_A05", DAMAGE, BASIC, "After your originally 2-turn attack fails its ordinary hit check.", "元2ターンの自分の攻撃が通常の命中判定で外れた後。", OWN_MISS) { x, _ -> x.skill && x.originalCastTurns == 2 },
        rule("PALADIN_A06", COST, LINKED_SUPPORT, "After an attack skill consumes Retribution Preparation and deals HP damage.", "応報の構えを消費した攻撃スキルがHPダメージを与えた後。", OWN_HIT, p(1)) { x, _ -> x.skill && x.hpDamage > 0 && x.consumed(p(2)) },
        rule("PALADIN_A07", DAMAGE, SKILL, "After Oath of Sanctuary actually blocks one new harmful status.", "聖域の誓いが新たな有害状態を実際に1回防いだ後。", STATUS_BLOCKED) { x, _ -> x.causedBy(p(6)) },
        rule("PALADIN_B01", COST, ANY_SUPPORT, "After an attack skill consumes Sacred Focus and deals HP damage.", "聖なる集中を消費した攻撃スキルがHPダメージを与えた後。", OWN_HIT) { x, _ -> x.skill && x.hpDamage > 0 && x.consumed(p(9)) },
        rule("PALADIN_B02", DAMAGE, ONE_TURN_SKILL, "While the enemy has a dispellable support attack buff.", "敵に解除可能な補助スキルの攻撃強化が残っている間。") { x, _ -> x.targetHasAttackBuff },
        rule("PALADIN_B03", ACCURACY, ONE_TURN_ATTACK, "After an enemy support attack buff expires naturally.", "敵の補助スキルの攻撃強化が自然終了した後。", ENEMY_SUPPORT_EXPIRED) { x, _ -> x.supportWasAttackBuff },
        rule("PALADIN_B04", COST, LINKED_SUPPORT, "After Guardian's Vow expires naturally without consuming its guard charge.", "守護の誓約の防御権を未使用のまま自然終了した後。", SUPPORT_EXPIRED, p(9)) { x, _ -> x.causedBy(p(1)) && x.supportHadUnusedCharges },
        rule("PALADIN_B05", DAMAGE, BASIC, "At completion, your HP percentage is at least 25 points below the enemy's.", "完了時、自分のHP割合が敵より25ポイント以上低い時。") { x, _ -> x.targetHpRatio - x.selfHpRatio >= .25 },
        rule("PALADIN_B06", DAMAGE, ANY_ATTACK, "After Divine Judgment actually removes an enemy support buff.", "神聖な裁きが敵の補助強化を実際に解除した後。", DISPELLED) { x, _ -> x.causedBy(p(3)) },
        rule("PALADIN_B07", COST, LINKED_SUPPORT, "After Vow of Purity actually removes a harmful ongoing status.", "清浄の誓いが有害な持続状態を実際に解除した後。", CLEANSED, p(9)) { x, _ -> x.causedBy(p(4)) },
        rule("PALADIN_C01", COST, LINKED_SUPPORT, "Begin Guardian's Vow while HP is at most 30%.", "HP30%以下で守護の誓約を開始。", support = p(1)) { x, _ -> x.selfHpRatio <= .3 },
        rule("PALADIN_C02", DAMAGE, ONE_TURN_ATTACK, "Survive one direct hit that takes HP from above 30% to at most 30%.", "1回の直接攻撃でHPが30%超から30%以下になり、生存した後。", TAKEN_HIT) { x, _ -> x.hpDamage > 0 && x.selfHpBeforeRatio > .3 && x.selfHpRatio <= .3 },
        rule("PALADIN_C03", DAMAGE, SKILL, "Complete an attack consuming Sacred Focus while HP is at most 50%.", "HP50%以下で、聖なる集中を消費する攻撃を完了。") { x, _ -> x.selfHpRatio <= .5 && (x.active(p(9)) || x.consumed(p(9))) },
        rule("PALADIN_C04", REDUCTION, INCOMING_BASIC, "After a basic HP hit when no owned paid attack is affordable at its current effective MP cost.", "通常攻撃がHPに命中し、所持する有料攻撃の実効MP費用を1つも払えない時。", OWN_HIT) { x, _ -> x.isBasic && x.hpDamage > 0 && x.selfCannotAffordOwnedSkill },
        rule("PALADIN_C05", REDUCTION, INCOMING_SKILL, "While HP is at most 30% and you are casting an originally 2–3-turn attack.", "HP30%以下で、元2～3ターンの攻撃を詠唱中なら。") { x, _ -> x.selfHpRatio <= .3 && x.selfCastOriginalTurns in 2..3 },
        rule("PALADIN_C06", DAMAGE, ANY_ATTACK, "After Shelter the Weak actually absorbs damage.", "弱者の守護がダメージを実際に吸収した後。", SHIELD_ABSORBED) { x, _ -> x.causedBy(p(7)) && x.absorbed > 0 },
        rule("PALADIN_C07", COST, LINKED_SUPPORT, "After Lay on Hands restores HP or grants a positive shield through its core.", "按手がHPを回復、または核心の変形で正のシールドを付与した後。", SUPPORT_COMPLETED, p(9)) { x, _ -> x.causedBy(p(8)) && (x.healing > 0 || x.selfShield > 0) },
    ).toMap().also { check(it.size == 126) }
}
