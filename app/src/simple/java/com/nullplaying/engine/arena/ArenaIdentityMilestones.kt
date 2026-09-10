package com.nullplaying.engine.arena

import kotlin.math.ceil

/** Authored bonuses are resolved once into the battle snapshot. They never modify saved ranks. */
internal object ArenaIdentityMilestones {
    data class Bonus(val key: String, val amount: Double, val ko: String, val en: String, val ja: String) {
        fun text(language: String) = when(language.substringBefore('-')) { "en" -> en; "ja" -> ja; else -> ko }
    }
    private fun b(key: String, value: Double, ko: String, en: String, ja: String) = Bonus(key,value,ko,en,ja)
    private fun duration()=b("duration",1.0,"효과 유지 시간 +1턴","Effect lasts 1 extra turn","効果の持続時間+1ターン")
    private fun charges()=b("charges",1.0,"효과 적용 횟수 +1회","Applies to 1 additional action","効果の適用回数+1回")
    private fun damage(value: Double=3.0)=b("magnitude",value,"조건 충족 시 추가 피해 +${value.toInt()}%","Conditional damage bonus +${value.toInt()} percentage points","条件達成時の追加ダメージ+${value.toInt()}ポイント")
    private fun guard(value: Double=2.0)=b("magnitude",value,"피해 감소량 +${value.toInt()}%","Damage reduction +${value.toInt()} percentage points","ダメージ軽減量+${value.toInt()}ポイント")
    private fun heal()=b("magnitude",1.0,"회복량: 최대 HP의 1% 추가","Healing increased by 1% of max HP","回復量が最大HPの1%分増加")
    private fun shield()=b("magnitude",1.0,"보호막: 최대 HP의 1% 추가","Shield increased by 1% of max HP","バリアが最大HPの1%分増加")
    private fun control()=b("control_bonus_pp",3.0,"행동 방해 성공률 +3%","Control success chance +3 percentage points","行動妨害の成功率+3ポイント")
    private fun removal()=b("removal_damage",6.0,"해제 성공 후 다음 공격 피해 +6% (2턴)","After removing an effect: next attack damage +6% (2 turns)","解除成功後、次の攻撃ダメージ+6%（2ターン）")
    private fun removalMaster()=b("removal_damage",6.0,"해제 성공 후 추가 피해 6% → 12%","Damage bonus after removing an effect: 6% → 12%","解除成功後の追加ダメージ：6% → 12%")
    private fun accuracy()=b("on_hit_accuracy",3.0,"명중 후 다음 공격 명중률 +3% (2턴)","On hit: next attack accuracy +3% (2 turns)","命中後、次の攻撃命中率+3%（2ターン）")
    private fun hitGuard()=b("on_hit_guard",3.0,"명중 후 받는 피해 3% 감소 (2턴)","On hit: incoming damage reduced by 3% (2 turns)","命中後、受けるダメージ3%減少（2ターン）")

    fun at(s: ArenaIdentitySkill, rank: Int): Bonus {
        require(rank==5 || rank==10)
        ArenaClassMilestones.at(s,rank)?.let { return it }
        if(!s.support) {
            if(rank==10 && s.slot=="A01") return b("original_capstone",0.0,"","","")
            if(rank==5) return when(s.kind) {
                "accuracy" -> b("after_miss_damage",6.0,"최근 2턴 내 빗나갔다면 피해 +6%","Damage +6% after a miss within 2 turns","直近2ターン以内に攻撃が外れた場合、ダメージ+6%")
                "dispel","self_cleanse","remove_illusion" -> removal()
                "delay" -> control()
                "mp_drain" -> b("magnitude",1.0,"상대 MP 감소량 +1","Drains 1 more enemy MP","相手のMP減少量+1")
                "heal_on_hit_hp" -> heal()
                "shield_on_hit_hp" -> shield()
                "poison_tick","burn_tick","bleed_tick" -> b("magnitude",3.0,"지속 피해: 공격력의 3% 추가","Each damage-over-time tick gains 3% of attack","継続ダメージに攻撃力の3%分を追加")
                "shield_extra" -> b("magnitude",8.0,"보호막에 주는 추가 피해 +8%","Bonus damage to shields +8 percentage points","バリアへの追加ダメージ+8ポイント")
                "shield_bypass" -> b("shield_target_damage",6.0,"보호막이 있는 상대에게 피해 +6%","Damage +6% against a shielded enemy","バリアのある相手へのダメージ+6%")
                "mitigation_ignore" -> b("magnitude",3.0,"상대 피해 감소 효과 무시 비율 +3%","Ignores 3% more of enemy damage reduction","相手のダメージ軽減を無視する割合+3ポイント")
                "weaken_damage" -> b("magnitude",2.0,"상대 피해 감소량 +2%","Enemy damage reduction effect +2 percentage points","相手のダメージ減少量+2ポイント")
                "slow_accuracy" -> b("magnitude",2.0,"상대 명중률 감소량 +2%","Enemy accuracy penalty +2 percentage points","相手の命中率低下量+2ポイント")
                "evasion_reduction" -> b("magnitude",2.0,"상대 회피율 감소량 +2%","Enemy evasion penalty +2 percentage points","相手の回避率低下量+2ポイント")
                "evasion_on_hit" -> b("magnitude",2.0,"명중 후 회피율 증가량 +2%","On-hit evasion bonus +2 percentage points","命中後の回避率ボーナス+2ポイント")
                "mana_return" -> b("magnitude",1.0,"명중 시 MP 회수량 +1","Restores 1 more MP on hit","命中時のMP回復量+1")
                "burn_release" -> b("magnitude",5.0,"남은 화상의 즉시 피해 전환율 +5%","Burn damage converted immediately +5 percentage points","残る火傷を即時ダメージに変える割合+5ポイント")
                "heal_reduction" -> b("magnitude",3.0,"상대 회복 방해량 +3%","Enemy healing penalty +3 percentage points","相手の回復量低下+3ポイント")
                "followup_accuracy" -> b("followup_damage_bonus",3.0,"명중 강화 중 다음 공격 피해 +3%","Next accuracy-boosted attack deals +3% damage","命中強化中、次の攻撃ダメージ+3%")
                "followup_damage" -> b("magnitude",3.0,"후속 공격 피해 보너스 +3%","Follow-up damage bonus +3 percentage points","次の攻撃のダメージボーナス+3ポイント")
                "damage_bonus" -> b("magnitude",3.0,"이 공격의 추가 피해 +3%","This attack's damage bonus +3 percentage points","この攻撃の追加ダメージ+3ポイント")
                else -> damage()
            }
            return when(s.kind) {
                "followup_damage","followup_accuracy" -> charges()
                "poison_tick","burn_tick","bleed_tick","weaken_damage","slow_accuracy","heal_reduction","shield_on_hit_hp" -> duration()
                "execute_bonus" -> b("execute_threshold",10.0,"추가 피해 대상: HP 35% → 45% 이하","Damage bonus applies at 45% enemy HP instead of 35%","追加ダメージの条件：相手HP35%以下 → 45%以下")
                "after_miss_bonus" -> b("miss_window",1.0,"빗나감 후 추가 피해 기회 2턴 → 3턴","Post-miss damage window: 2 → 3 turns","攻撃が外れた後の追加ダメージ有効期間：2 → 3ターン")
                "dispel","self_cleanse","remove_illusion" -> removalMaster()
                "delay" -> b("control_accuracy",6.0,"행동 방해 성공 후 다음 명중률 +6% (2턴)","After successful control: next accuracy +6% (2 turns)","行動妨害成功後、次の命中率+6%（2ターン）")
                "heal_on_hit_hp" -> b("heal_cleanse",1.0,"실제로 회복하면 자신의 상태이상 1개 해제","After restoring HP: removes 1 of your debuffs","HPを実際に回復した場合、自分の弱体効果を1つ解除")
                "mp_drain" -> b("drain_refund",1.0,"상대 MP를 줄이면 MP 1 회수","Restores 1 MP after draining enemy MP","相手のMPを減らした場合、MPを1回復")
                "shield_extra","shield_bypass","mitigation_ignore","recent_pierce_bonus" -> hitGuard()
                else -> accuracy()
            }
        }
        if(rank==5) return when(s.kind) {
            "CLEANSE","CLEANSE_ACCURACY","DISPEL","JUDGMENT","SEAL","REVEAL" -> removal()
            "TAUNT","TRAP","SLEEP" -> control()
            "MIRROR" -> b("mirror_chance_pp",3.0,"분신의 회피 성공률 25% → 28%","Mirror evasion chance: 25% → 28%","分身の回避成功率：25% → 28%")
            "STATUS_GUARD" -> duration()
            "DAMAGE_CAP" -> b("magnitude",-1.0,"큰 피해 상한: 최대 HP 기준 1% 감소","Heavy-hit cap reduced by 1% of max HP","大ダメージの上限が最大HPの1%分減少")
            "HEAL","BANDAGE","LAY_HANDS" -> heal()
            "REGEN" -> b("magnitude",0.3,"매 턴 회복량: 최대 HP의 0.3% 추가","Each regeneration tick gains 0.3% of max HP","毎ターンの回復量が最大HPの0.3%分増加")
            "SHIELD","LOW_SHIELD" -> shield()
            "IRON","RESTRAINT","BASIC_GUARD","SKILL_GUARD","GRACE" -> guard()
            "WRIST" -> b("magnitude",2.0,"상대 공격 피해 감소량 +2%","Enemy attack damage penalty +2 percentage points","相手の攻撃ダメージ低下量+2ポイント")
            "SMOKE" -> b("magnitude",2.0,"상대 명중률 감소량 +2%","Enemy accuracy penalty +2 percentage points","相手の命中率低下量+2ポイント")
            "EVASION","VIGILANCE" -> b("magnitude",2.0,"추가 회피 +2% (합산 상한 유지)","Extra evasion +2 percentage points (total cap unchanged)","追加回避+2ポイント（合計上限は維持）")
            "MP_DRAIN" -> b("magnitude",1.0,"타격당 상대 MP 감소량 +1","Drains 1 more enemy MP per hit","命中ごとの相手MP減少量+1")
            "POISON_ACCELERATE" -> b("poison_first_tick",0.1,"추가 독의 첫 피해 보너스 20% → 30%","Added poison's first-tick bonus: 20% → 30%","追加した毒の初回ダメージボーナス：20% → 30%")
            "SANCTUARY" -> b("resistance_bonus_pp",3.0,"행동 방해 저항 +3%","Control resistance +3 percentage points","行動妨害への耐性+3ポイント")
            "STABILIZE" -> b("skill_damage_bonus_percent",3.0,"준비한 스킬의 피해 보너스 +3%","Prepared skill damage bonus +3 percentage points","準備したスキルのダメージボーナス+3ポイント")
            "BASIC_PIERCE","PIERCE","OBSERVE","PHASE" -> b("next_attack_damage_bonus_percent",3.0,"관통 공격의 피해 보너스 +3%","Piercing attack damage bonus +3 percentage points","貫通攻撃のダメージボーナス+3ポイント")
            "POISON_COAT","BURN_PREP","EMBER" -> b("magnitude",3.0,"지속 피해: 공격력의 3% 추가","Each damage-over-time tick gains 3% of attack","継続ダメージに攻撃力の3%分を追加")
            "BASIC_SHATTER","SKILL_SHATTER" -> b("magnitude",8.0,"보호막에 주는 추가 피해 +8%","Bonus damage to shields +8 percentage points","バリアへの追加ダメージ+8ポイント")
            "HEAL_BLOCK_PREP" -> b("magnitude",3.0,"상대 회복 방해량 +3%","Enemy healing penalty +3 percentage points","相手の回復量低下+3ポイント")
            "HEAL_TRACK" -> b("heal_track_window",1.0,"회복 추적 유효 기간 2턴 → 3턴","Healing-tracking window: 2 → 3 turns","回復を追跡できる期間：2 → 3ターン")
            "LIFESTEAL" -> b("magnitude",2.0,"피해 흡수 회복 비율 +2%","Life-steal ratio +2 percentage points","与えたダメージの回復割合+2ポイント")
            "TRUTH" -> b("magnitude",5.0,"은신·분신 약화 비율 +5%","Stealth and mirror suppression +5 percentage points","隠密・分身の弱体化率+5ポイント")
            else -> b("magnitude",3.0,"강화 공격의 피해 보너스 +3%","Empowered attack damage bonus +3 percentage points","強化した攻撃のダメージボーナス+3ポイント")
        }
        return when(s.kind) {
            "CLEANSE","CLEANSE_ACCURACY","DISPEL","JUDGMENT","SEAL","REVEAL" -> removalMaster()
            "TAUNT","TRAP","SLEEP" -> b("control_accuracy",6.0,"행동 방해 성공 후 다음 명중률 +6% (2턴)","After successful control: next accuracy +6% (2 turns)","行動妨害成功後、次の命中率+6%（2ターン）")
            "HEAL","BANDAGE","LAY_HANDS" -> b("heal_shield",2.0,"실제로 회복하면 최대 HP 2% 보호막 (2턴)","After restoring HP: shields 2% max HP (2 turns)","HPを実際に回復した場合、最大HPの2%のバリア（2ターン）")
            "MIRROR","STATUS_GUARD" -> b("magnitude",1.0,"방어 판정 횟수 +1회","Blocks or checks 1 additional time","防御判定回数+1回")
            "POISON_ACCELERATE" -> b("poison_duration",1.0,"추가한 독의 지속 시간 +1턴","Added poison lasts 1 extra turn","追加した毒の持続時間+1ターン")
            "SKILL_GUARD","GRACE" -> duration()
            "SHIELD","LOW_SHIELD" -> b("shield_guard",3.0,"보호막 생성 후 받는 피해 3% 감소 (2턴)","After shielding: incoming damage reduced by 3% (2 turns)","バリア生成後、受けるダメージ3%減少（2ターン）")
            "HEAL_TRACK" -> b("heal_track_cap",5.0,"추적 피해 상한: 공격력 25% → 30%","Tracked damage cap: 25% → 30% of attack","回復追跡の追加ダメージ上限：攻撃力の25% → 30%")
            else -> if(s.charges>0) charges() else duration()
        }
    }

    fun apply(base: ArenaIdentitySkill): ArenaIdentitySkill {
        // Reverse the old blanket rank-ten rebate. Other ranks and individually authored costs stay intact.
        var s=if(base.capstone=="mp_efficiency") base.copy(mp=ceil(base.mp/.92).toInt(),capstone="") else base
        for(rank in listOf(5,10)) if(base.rank>=rank) {
            val bonus=at(base,rank)
            s=when(bonus.key) {
                "magnitude" -> s.copy(magnitude=s.magnitude+bonus.amount)
                "duration" -> s.copy(duration=s.duration+bonus.amount.toInt())
                "charges" -> s.copy(charges=s.charges+bonus.amount.toInt())
                "original_capstone" -> s
                else -> s.copy(numbers=s.numbers+(bonus.key to (s.n(bonus.key)+bonus.amount)))
            }
        }
        return s
    }
}
