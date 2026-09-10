package com.nullplaying.engine.arena

import com.nullplaying.model.HeroClass

/** Class-specific follow-through replaces shared master bonuses. Base skill roles stay intact. */
internal object ArenaClassMilestones {
    private fun b(key: String, value: Double, ko: String, en: String, ja: String) =
        ArenaIdentityMilestones.Bonus(key,value,ko,en,ja)
    private fun counter()=b("counter_damage",8.0,"최근 2턴 내 피격됐다면 피해 +8%","Damage +8% if hit within the last 2 turns","直近2ターン以内に被弾した場合、ダメージ+8%")
    private fun bloodied()=b("bloodied_damage",8.0,"자신의 HP가 절반 이하면 피해 +8%","Damage +8% while your HP is at or below half","自分のHPが半分以下ならダメージ+8%")
    private fun opening()=b("opening_damage",8.0,"상대 HP가 70% 이상이면 피해 +8%","Damage +8% against enemies with at least 70% HP","相手のHPが70%以上ならダメージ+8%")
    private fun precise(value: Int=8)=b("consecutive_hit_damage",value.toDouble(),"직전 공격이 명중했다면 피해 +$value%","Damage +$value% if your previous attack hit","直前の攻撃が命中していればダメージ+$value%")
    private fun mana(value: Int=8)=b("high_mana_damage",value.toDouble(),"MP가 절반 이상 남아 있으면 피해 +$value%","Damage +$value% while at least half your MP remains","MPが半分以上残っていればダメージ+$value%")
    private fun healed()=b("recent_heal_damage",8.0,"최근 3턴 내 HP를 회복했다면 피해 +8%","Damage +8% after restoring HP within 3 turns","直近3ターン以内にHPを回復していればダメージ+8%")
    private fun guarded()=b("guarded_damage",8.0,"보호막이나 피해 감소 효과가 있으면 피해 +8%","Damage +8% while shielded or under damage reduction","バリアまたはダメージ軽減の効果中はダメージ+8%")
    private fun wound()=b("bleed_on_hit",5.0,"명중 시 출혈: 공격력의 5%씩 2턴","On hit: bleed for 5% of attack each turn for 2 turns","命中時、攻撃力の5%の出血ダメージを2ターン")
    private fun venom()=b("poison_on_hit",5.0,"명중 시 독: 공격력의 5%씩 2턴","On hit: poison for 5% of attack each turn for 2 turns","命中時、攻撃力の5%の毒ダメージを2ターン")
    private fun flame()=b("burn_on_hit",5.0,"명중 시 화상: 공격력의 5%씩 2턴","On hit: burn for 5% of attack each turn for 2 turns","命中時、攻撃力の5%の火傷ダメージを2ターン")
    private fun step()=b("hit_evasion",3.0,"명중 후 회피 +3% (2턴)","On hit: evasion +3% for 2 turns","命中後、回避+3%（2ターン）")
    private fun ignore()=b("native_ignore",4.0,"상대의 기본 회피 4% 무시","Ignores 4 percentage points of innate evasion","相手の基礎回避を4ポイント無視")
    private fun aimNext()=b("next_ignore_on_hit",4.0,"명중 후 다음 공격은 기본 회피 4% 무시 (5턴)","On hit: next attack ignores 4 points of innate evasion (5 turns)","命中後、次の攻撃は基礎回避を4ポイント無視（5ターン）")
    private fun holy()=b("hit_heal",1.0,"명중 시 최대 HP의 1% 회복","On hit: restores 1% of max HP","命中時、最大HPの1%を回復")
    private fun oath()=b("hit_shield",1.0,"명중 시 최대 HP 1% 보호막 (2턴)","On hit: shields 1% of max HP for 2 turns","命中時、最大HPの1%のバリア（2ターン）")
    private fun refund()=b("hit_mana_refund",1.0,"명중 시 MP 1 회수","Restores 1 MP on hit","命中時、MPを1回復")
    private fun duration()=b("duration",1.0,"효과 유지 시간 +1턴","Effect lasts 1 extra turn","効果の持続時間+1ターン")
    private fun charges()=b("charges",1.0,"효과 적용 횟수 +1회","Applies to 1 additional action","効果の適用回数+1回")
    private fun nextBleed()=b("next_bleed",5.0,"다음 공격에 출혈: 공격력의 5%씩 2턴 (5턴 내)","Next attack within 5 turns: bleed for 5% of attack for 2 turns","5ターン以内の次の攻撃で、攻撃力の5%の出血を2ターン")
    private fun nextPoison()=b("next_poison",5.0,"다음 공격에 독: 공격력의 5%씩 2턴 (5턴 내)","Next attack within 5 turns: poison for 5% of attack for 2 turns","5ターン以内の次の攻撃で、攻撃力の5%の毒を2ターン")
    private fun nextBurn()=b("next_burn",5.0,"다음 주문에 화상: 공격력의 5%씩 2턴 (5턴 내)","Next spell within 5 turns: burns for 5% of attack for 2 turns","5ターン以内の次の魔法で、攻撃力の5%の火傷を2ターン")
    private fun nextAim()=b("next_ignore",4.0,"다음 공격은 기본 회피 4% 무시 (5턴 내)","Next attack within 5 turns ignores 4 points of innate evasion","5ターン以内の次の攻撃は基礎回避を4ポイント無視")
    private fun castHeal()=b("cast_heal",1.0,"사용 시 최대 HP의 1% 회복","Restores 1% of max HP on use","使用時、最大HPの1%を回復")
    private fun castShield()=b("cast_shield",1.0,"사용 시 최대 HP 1% 보호막 (2턴)","On use: shields 1% of max HP for 2 turns","使用時、最大HPの1%のバリア（2ターン）")
    private val removals=setOf("dispel","self_cleanse","remove_illusion","CLEANSE","CLEANSE_ACCURACY","DISPEL","JUDGMENT","SEAL","REVEAL")

    fun at(s: ArenaIdentitySkill, rank: Int): ArenaIdentityMilestones.Bonus? {
        val c=ArenaIdentityCatalog.find(s.id)?.heroClass ?: return null
        // Adept improves the existing technique; accuracy skills also establish a class motif.
        if(rank==5) {
            if(!s.support && s.kind=="accuracy") return when(c) {
                HeroClass.WARRIOR->counter(); HeroClass.ROGUE->opening(); HeroClass.RANGER->precise(6)
                HeroClass.MAGE->mana(4); HeroClass.CLERIC->healed(); HeroClass.PALADIN->guarded()
            }
            if(c==HeroClass.RANGER && s.kind=="remove_illusion") return b("removal_damage",8.0,"분신 제거 후 다음 공격 피해 +8% (2턴)","After removing a mirror image: next attack damage +8% (2 turns)","分身を解除した後、次の攻撃ダメージ+8%（2ターン）")
            if(s.kind in removals) return when(c) {
                HeroClass.ROGUE->b("removal_poison",4.0,"해제 성공 후 다음 공격에 독 (공격력 4%씩 2턴)","After removal: next attack poisons for 4% of attack for 2 turns","解除成功後、次の攻撃で攻撃力の4%の毒を2ターン")
                HeroClass.RANGER->b("removal_ignore",4.0,"해제 성공 후 다음 공격은 기본 회피 4% 무시","After removal: next attack ignores 4 points of innate evasion","解除成功後、次の攻撃は基礎回避を4ポイント無視")
                HeroClass.MAGE->b("removal_refund",2.0,"해제 성공 시 MP 2 회수","Restores 2 MP after successful removal","解除成功時、MPを2回復")
                HeroClass.CLERIC->b("removal_heal",1.0,"해제 성공 시 최대 HP의 1% 회복","Restores 1% of max HP after successful removal","解除成功時、最大HPの1%を回復")
                HeroClass.PALADIN->b("removal_shield",1.0,"해제 성공 시 최대 HP 1% 보호막 (2턴)","After removal: shields 1% of max HP for 2 turns","解除成功時、最大HPの1%のバリア（2ターン）")
                else->null
            }
            return null
        }
        if(!s.support && s.slot=="A01") return null // Keep six different, established first-skill masters.
        if(s.support) return when(c) {
            HeroClass.WARRIOR -> when(s.kind) {
                "SHOUT","PURSUIT"->charges(); "IRON"->duration()
                "BANDAGE"->b("heal_guard",4.0,"회복 성공 후 받는 피해 4% 감소 (2턴)","After healing: damage taken -4% for 2 turns","回復成功後、受けるダメージ4%減少（2ターン）")
                "TAUNT"->b("control_bonus_pp",5.0,"도발 성공률 추가 +5%","Taunt success chance +5 percentage points","挑発の成功率がさらに5ポイント増加")
                "CLEANSE_ACCURACY"->null
                else->nextBleed()
            }
            HeroClass.ROGUE -> when(s.kind) {
                "EVASION","POISON_COAT"->charges(); "POISON_ACCELERATE"->null
                "SMOKE","STEALTH"->nextPoison()
                "REVEAL"->stepOnUse()
                else->nextPoison()
            }
            HeroClass.RANGER -> when(s.kind) {
                "VIGILANCE" -> charges()
                "TRAP"->null; "HEAL_TRACK"->null; "RAPID"->charges()
                "CLEANSE_ACCURACY","REVEAL"->b("removal_ignore",2.0,"해제 후 기본 회피 무시 4% → 6%","Innate evasion ignored after removal: 4 → 6 points","解除後の基礎回避無視：4 → 6ポイント")
                else->nextAim()
            }
            HeroClass.MAGE -> when(s.kind) {
                "SLEEP"->b("control_refund",2.0,"수면 성공 시 MP 2 회수","Restores 2 MP after successful sleep","睡眠成功時、MPを2回復")
                "MIRROR"->null; "DISPEL"->nextBurn()
                "BURN_PREP","EMBER"->charges()
                else->nextBurn()
            }
            HeroClass.CLERIC -> when(s.kind) {
                "HEAL","REGEN","SANCTUARY","STATUS_GUARD"->null
                else->castHeal()
            }
            HeroClass.PALADIN -> when(s.kind) {
                "LAY_HANDS","SHIELD","LOW_SHIELD"->null
                "CLEANSE","JUDGMENT"->b("removal_shield",1.0,"해제 성공 후 보호막: 최대 HP 1% → 2%","Shield after removal: 1% → 2% of max HP","解除成功後のバリア：最大HPの1% → 2%")
                else->castShield()
            }
        }
        return when(c) {
            HeroClass.WARRIOR -> when(s.kind) {
                "bleed_tick"->duration(); "weaken_damage","delay"->counter()
                "shield_extra","shield_bypass","mitigation_ignore"->wound()
                "followup_damage"->b("next_basic_on_hit",20.0,"명중 후 다음 일반 공격 피해 +20% (3턴)","On hit: next basic attack damage +20% (3 turns)","命中後、次の通常攻撃ダメージ+20%（3ターン）")
                "heal_on_hit_hp","self_cleanse"->bloodied()
                else->counter()
            }
            HeroClass.ROGUE -> when(s.kind) {
                "poison_bonus"->b("poison_extend",1.0,"명중 시 자신이 건 독 1개를 1턴 연장 (최대 4턴)","On hit: extends one of your poisons by 1 turn (up to 4 turns)","命中時、自分が付与した毒を1つ、1ターン延長（最大4ターン）")
                "poison_tick","bleed_tick","heal_reduction"->duration()
                "followup_damage","followup_accuracy","heal_on_hit_hp","remove_illusion"->step()
                "execute_bonus","status_bonus"->opening()
                else->venom()
            }
            HeroClass.RANGER -> when(s.kind) {
                "followup_damage"->charges(); "followup_accuracy","heal_on_hit_hp","consecutive_bonus"->aimNext()
                "weaken_damage","slow_accuracy","heal_reduction"->duration()
                "after_miss_bonus","delay","execute_bonus"->null
                "mitigation_ignore","shield_bypass","accuracy","remove_illusion"->ignore()
                else->precise()
            }
            HeroClass.MAGE -> when(s.kind) {
                "burn_release"->b("detonation_refund",2.0,"화상 소모에 성공하면 MP 2 회수","Restores 2 MP after consuming a burn","火傷を消費できた場合、MPを2回復")
                "burn_tick","weaken_damage","slow_accuracy"->duration()
                "mp_drain","mitigation_ignore","shield_bypass","status_bonus"->refund()
                "delay"->b("control_refund",2.0,"행동 방해 성공 시 MP 2 회수","Restores 2 MP after successful control","行動妨害成功時、MPを2回復")
                "preparing_bonus","buffed_target_bonus","execute_bonus","burn_bonus"->mana()
                else->flame()
            }
            HeroClass.CLERIC -> when(s.kind) {
                "heal_on_hit_hp"->null; "shield_on_hit_hp"->holy()
                "weaken_damage","heal_reduction"->duration()
                "dispel","self_cleanse"->healed()
                "delay"->b("control_heal",1.0,"행동 방해 성공 시 최대 HP의 1% 회복","Restores 1% of max HP after successful control","行動妨害成功時、最大HPの1%を回復")
                "execute_bonus","status_bonus","recent_support_bonus"->healed()
                else->holy()
            }
            HeroClass.PALADIN -> when(s.kind) {
                "shield_on_hit_hp","shield_bypass","mitigation_ignore"->guarded()
                "heal_on_hit_hp"->b("heal_shield",2.0,"회복 성공 시 최대 HP 2% 보호막 (2턴)","After healing: shields 2% max HP for 2 turns","回復成功時、最大HPの2%のバリア（2ターン）")
                "weaken_damage"->duration()
                "delay"->b("control_shield",1.0,"행동 방해 성공 시 최대 HP 1% 보호막 (2턴)","After control: shields 1% max HP for 2 turns","行動妨害成功時、最大HPの1%のバリア（2ターン）")
                else->oath()
            }
        }
    }

    private fun stepOnUse()=b("cast_evasion",3.0,"사용 후 회피 +3% (2턴)","Evasion +3% for 2 turns after use","使用後、回避+3%（2ターン）")
}
