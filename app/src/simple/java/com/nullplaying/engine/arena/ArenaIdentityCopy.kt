package com.nullplaying.engine.arena

import java.util.Locale
import kotlin.math.roundToInt

/** Player-facing copy reads the same frozen numeric definition as battle issuance. */
object ArenaIdentityCopy {
    fun preview(id: String,rank: Int): ArenaIdentitySkill {
        val d=requireNotNull(ArenaIdentityCatalog.find(id))
        return d.resolve(rank,ArenaFighterInput("copy-preview",d.heroClass,100,
            ArenaCoreStats(10.0,10.0,10.0,10.0,10.0,10.0,100.0,100.0),emptyList()))
    }
    private fun number(v: Double)=if(v==v.toInt().toDouble()) v.toInt().toString() else String.format(Locale.ROOT,"%.1f",v)
    private fun tr(language: String,ko: String,en: String,ja: String)=when(language.substringBefore('-')) {
        "en"->en;"ja"->ja;else->ko
    }
    fun timing(id: String,rank: Int,language: String,nextRank: Int?=null): String {
        val s=preview(id,rank)
        val nextMp=nextRank?.let { preview(id,it).mp }
        val mp=if(nextMp!=null && nextMp!=s.mp) "${s.mp}→$nextMp" else "${s.mp}"
        val action=tr(language,"행동 ${s.actionTurns}턴","${s.actionTurns}-turn action","行動${s.actionTurns}ターン")
        val cooldown=if(s.once) tr(language,"전투당 1회","Once per battle","1戦につき1回")
            else tr(language,"재사용 대기 ${s.cooldown}턴","Cooldown: ${s.cooldown} turns","再使用待機${s.cooldown}ターン")
        return listOfNotNull(action,"MP $mp",cooldown,
            if(s.earliestTurn>1) tr(language,"${s.earliestTurn}턴부터","From turn ${s.earliestTurn}","${s.earliestTurn}ターン目から") else null).joinToString(" · ")
    }
    fun capstone(id: String,language: String): String? = milestone(id,10,language)
    fun milestone(id: String,rank: Int,language: String): String {
        val bonus=ArenaIdentityMilestones.at(preview(id,rank),rank)
        return if(bonus.key=="original_capstone") requireNotNull(originalCapstone(id,language)) else bonus.text(language)
    }
    private fun originalCapstone(id: String,language: String): String? {
        val s=preview(id,10)
        if(s.support) return null
        return when(s.capstone) {
            "next_basic_damage" -> tr(language,"명중 후 다음 일반 공격 피해 +12% (2턴)","On hit: next basic attack damage +12% (2 turns)","命中後、次の通常攻撃ダメージ+12%（2ターン）")
            "bleed" -> tr(language,"명중 시 출혈 부여 (2턴, 1중첩)","On hit: apply bleeding (2 turns, 1 stack)","命中時、出血を付与（2ターン、1重複まで）")
            "native_evasion_ignore" -> tr(language,"상대의 기본 회피 2% 무시","Ignores 2 percentage points of innate evasion","基礎回避を2ポイント無視")
            "mp_refund_on_hit" -> tr(language,"명중 시 MP 1 회수","Restores 1 MP on hit","命中時にMPを1回復")
            "heal_on_hit" -> tr(language,"명중 시 최대 HP 1.5% 회복 (대기 3턴)","On hit: heals 1.5% max HP (3-turn cooldown)","命中時、最大HPの1.5%回復（待機3ターン）")
            "shield_on_hit" -> tr(language,"명중 시 최대 HP 2% 보호막 (2턴)","On hit: shields 2% max HP (2 turns)","命中時、最大HPの2%のバリア（2ターン）")
            else -> null
        }
    }
    fun effect(id: String,rank: Int,language: String): String {
        val s=preview(id,rank);val v=number(s.magnitude);val d=s.duration;val c=s.charges
        fun t(ko: String,en: String,ja: String)=tr(language,ko,en,ja)
        fun n(k: String)=number(s.n(k))
        val text=if(!s.support) when(s.kind) {
            "accuracy"->t("명중률 +$v%","Accuracy +$v%","命中率+$v%")
            "followup_damage"->t("다음 공격 ${c}회 피해 +$v% (${d}턴)","Next $c attacks: damage +$v% ($d turns)","次の攻撃${c}回：ダメージ+$v%（${d}ターン）")
            "followup_accuracy"->t("다음 공격 ${c}회 명중률 +$v% (${d}턴)","Next $c attacks: accuracy +$v% ($d turns)","次の攻撃${c}回：命中率+$v%（${d}ターン）")
            "shield_extra"->t("보호막에 주는 피해 +$v%","Damage to shields +$v%","バリアへのダメージ+$v%")
            "weaken_damage"->t("상대 공격 피해 -$v% (${d}턴)","Enemy attack damage -$v% ($d turns)","相手の攻撃ダメージ-$v%（${d}ターン）")
            "bleed_tick","poison_tick","burn_tick"->{
                val label=when(s.kind){"bleed_tick"->t("출혈","Bleeding","出血");"poison_tick"->t("독","Poison","毒");else->t("화상","Burn","火傷")}
                t("$label: 공격력 $v% 피해 (${d}턴)","$label: $v% attack damage per turn ($d turns)","$label：攻撃力の$v%の継続ダメージ（${d}ターン）")
            }
            "delay"->t("명중 시 상대 행동을 1턴 늦출 수 있습니다","On hit, may delay the enemy action by 1 turn","命中時、相手の行動を1ターン遅らせることがあります")
            "mitigation_ignore"->t("상대 피해 감소 효과 $v% 무시","Pierces $v% of enemy damage reduction","相手のダメージ軽減効果を$v%貫通")
            "shield_bypass"->t("피해의 $v%가 보호막을 무시","$v% of damage bypasses shields","ダメージの$v%がバリアを無視")
            "heal_reduction"->t("상대 회복량 -$v% (${d}턴)","Enemy healing -$v% ($d turns)","相手の回復量-$v%（${d}ターン）")
            "status_bonus"->t("자신이 건 상태이상이 있으면 피해 +$v%","Damage +$v% against enemies with your debuff","自分が付与した弱体効果がある相手へのダメージ+$v%")
            "bleed_bonus"->t("자신이 건 출혈이 남아 있으면 피해 +$v%","Damage +$v% while the enemy has your bleed","自分が付与した出血が残る相手へのダメージ+$v%")
            "burn_bonus"->t("자신이 건 화상이 남아 있으면 피해 +$v%","Damage +$v% while the enemy has your burn","自分が付与した火傷が残る相手へのダメージ+$v%")
            "consecutive_bonus"->t("직전 공격이 명중했다면 피해 +$v%","Damage +$v% if your previous attack hit","直前の攻撃が命中していればダメージ+$v%")
            "evasion_on_hit"->t("명중 후 회피율 +$v% (${d}턴)","On hit: evasion +$v percentage points ($d turns)","命中後、回避率+${v}ポイント（${d}ターン）")
            "mana_return"->t("명중 시 MP ${s.magnitude.roundToInt()} 회수","Restores ${s.magnitude.roundToInt()} MP on hit","命中時、MPを${s.magnitude.roundToInt()}回復")
            "poison_bonus"->t("자신이 건 독이 남아 있으면 피해 +$v%","Damage +$v% while the enemy has your poison","自分が付与した毒が残る相手へのダメージ+$v%")
            "opening_bonus"->t("상대 HP가 70% 이상이면 피해 +$v%","Damage +$v% against enemies with at least 70% HP","相手のHPが70%以上ならダメージ+$v%")
            "guarded_bonus"->t("자신에게 보호막이나 피해 감소 효과가 있으면 피해 +$v%","Damage +$v% while you have a shield or damage reduction","自分にバリアかダメージ軽減効果があればダメージ+$v%")
            "evasion_reduction"->t("상대 회피율 -$v% (${d}턴)","Enemy evasion -$v percentage points ($d turns)","相手の回避率-${v}ポイント（${d}ターン）")
            "burn_release"->t("명중 시 상대에게 건 화상 1개 소모\n남은 화상 피해의 $v%를 즉시 가함 (상대 최대 HP의 5% 한도)","On hit: consumes one burn you inflicted\nDeals $v% of its remaining damage now (up to 5% of enemy max HP)","命中時、相手に付与した火傷を1つ消費\n残りダメージの$v%を即座に与える（相手の最大HPの5%まで）")
            "heal_on_hit_hp"->t("명중 시 최대 HP $v% 회복","On hit: heals $v% max HP","命中時、最大HPの$v%回復")
            "shield_on_hit_hp"->t("명중 시 최대 HP $v% 보호막 (${d}턴)","On hit: shields $v% max HP ($d turns)","命中時、最大HPの$v%のバリア（${d}ターン）")
            "damage_bonus"->t("이 공격 피해 +$v%","This attack deals +$v% damage","この攻撃のダメージ+$v%")
            "execute_bonus"->t("HP ${number(35+s.n("execute_threshold"))}% 이하 상대에게 피해 +$v%","Damage +$v% against enemies at ${number(35+s.n("execute_threshold"))}% HP or less","HP${number(35+s.n("execute_threshold"))}%以下の相手へのダメージ+$v%")
            "dispel"->t("명중 시 상대 강화 1개 해제","On hit: removes 1 enemy buff","命中時、相手の強化を1つ解除")
            "self_cleanse"->t("명중 시 자신의 상태이상 1개 해제","On hit: removes 1 of your debuffs","命中時、自分の弱体効果を1つ解除")
            "preparing_bonus"->t("행동 준비 중인 상대에게 피해 +$v%","Damage +$v% against a preparing enemy","行動準備中の相手へのダメージ+$v%")
            "slow_accuracy"->t("상대 명중률 -$v% (${d}턴)","Enemy accuracy -$v% ($d turns)","相手の命中率-$v%（${d}ターン）")
            "mp_drain"->t("명중 시 상대 MP ${s.magnitude.roundToInt()} 감소","On hit: drains ${s.magnitude.roundToInt()} enemy MP","命中時、相手のMPを${s.magnitude.roundToInt()}減少")
            "after_miss_bonus"->{
                val window=number(2+s.n("miss_window"))
                t("공격이 빗나간 뒤 ${window}턴 이내 명중 시\n이 스킬 피해 +$v% (1회)",
                    "If this skill hits within $window turns of your miss:\nDamage +$v% (once)",
                    "自分の攻撃が外れてから${window}ターン以内に命中すると\nこのスキルのダメージ+$v%（1回）")
            }
            "recent_support_bonus"->t("최근 2턴 내 보조 스킬을 쓴 상대에게 피해 +$v%","Damage +$v% if the enemy used support within 2 turns","直近2ターン以内に補助スキルを使った相手へのダメージ+$v%")
            "recent_fast_bonus"->t("최근 2턴 내 1턴 공격을 한 상대에게 피해 +$v%","Damage +$v% if the enemy used a one-turn attack within 2 turns","直近2ターン以内に1ターン攻撃をした相手へのダメージ+$v%")
            "recent_pierce_bonus"->t("최근 2턴 내 관통 공격을 한 상대에게 피해 +$v%","Damage +$v% if the enemy used a piercing attack within 2 turns","直近2ターン以内に貫通攻撃をした相手へのダメージ+$v%")
            "buffed_target_bonus"->t("강화 중인 상대에게 피해 +$v%","Damage +$v% against a buffed enemy","強化中の相手へのダメージ+$v%")
            "remove_illusion"->t("명중 시 분신 1개 제거","On hit: removes 1 mirror image","命中時、分身を1体解除")
            else->error("Missing attack copy: ${s.kind}")
        } else when(s.kind) {
            "VIGILANCE" -> t("추가 회피 $v% (${c}회, ${d}턴)\n일반 공격·공격 스킬 대상 · 합산 회피 최대 35%",
                "Extra evasion $v% ($c checks, $d turns)\nAgainst basic and skill attacks · total evasion capped at 35%",
                "追加回避$v%（${c}回、${d}ターン）\n通常攻撃・攻撃スキルが対象・合計回避は最大35%")
            "IRON","WRIST","RESTRAINT","BASIC_GUARD","SKILL_GUARD","GRACE"->{
                val who=when(s.kind){"WRIST"->t("상대 공격 피해","Enemy attack damage","相手の攻撃ダメージ");"BASIC_GUARD"->t("받는 일반 공격 피해","Incoming basic damage","受ける通常攻撃ダメージ");"SKILL_GUARD"->t("받는 스킬 피해","Incoming skill damage","受けるスキルダメージ");"GRACE"->t("받는 큰 피해","Incoming heavy damage","受ける大きなダメージ");else->t("받는 피해","Incoming damage","受けるダメージ")}
                "$who -$v%"+t(" (${d}턴)"," ($d turns)","（${d}ターン）")+if(s.kind=="RESTRAINT") t("\n자신의 피해 -5%","\nYour damage -5%","\n自分のダメージ-5%") else ""
            }
            "RESOLVE"->t("HP 50% 이하에서 공격 피해 +$v% (${d}턴)","At 50% HP or less: damage +$v% ($d turns)","HP50%以下で攻撃ダメージ+$v%（${d}ターン）")
            "SHOUT"->t("공격 피해 +$v% (${d}턴)","Attack damage +$v% ($d turns)","攻撃ダメージ+$v%（${d}ターン）")
            "COUNTER","RETRIBUTION"->t("피격 후 다음 공격 ${c}회 피해 +$v%","After taking damage: next $c attacks deal +$v% damage","被ダメージ後、次の攻撃${c}回のダメージ+$v%")
            "PURSUIT"->t("빗나간 후 다음 공격 ${c}회 피해 +$v%\n명중률 +6%","After a miss: next $c attacks deal +$v% damage\nAccuracy +6%","攻撃が外れた後、次の攻撃${c}回のダメージ+$v%\n命中率+6%")
            "OPPORTUNITY"->t("상대가 빗나간 후 다음 공격 ${c}회 피해 +$v%","After an enemy miss: next $c attacks deal +$v% damage","相手の攻撃が外れた後、次の攻撃${c}回のダメージ+$v%")
            "EXECUTE"->t("상대 HP 40% 이하에서 다음 공격 ${c}회 피해 +$v%","Against an enemy at 40% HP or less: next $c attacks deal +$v% damage","相手のHP40%以下で次の攻撃${c}回のダメージ+$v%")
            "CONDENSE","STEALTH"->t("다음 공격 ${c}회 피해 +$v%","Next $c attacks deal +$v% damage","次の攻撃${c}回のダメージ+$v%")
            "BASIC_SHATTER"->t("다음 일반 공격 ${c}회 보호막 피해 +$v%","Next $c basic attacks: shield damage +$v%","次の通常攻撃${c}回：バリアへのダメージ+$v%")
            "SKILL_SHATTER"->t("다음 스킬 ${c}회 보호막 피해 +$v%","Next $c skills: shield damage +$v%","次のスキル${c}回：バリアへのダメージ+$v%")
            "PHASE"->t("다음 ${c}회 피해 +${n("next_attack_damage_bonus_percent")}%\n피해 $v%가 보호막을 무시","Next $c attacks: damage +${n("next_attack_damage_bonus_percent")}%\n$v% of damage bypasses shields","次の攻撃${c}回：ダメージ+${n("next_attack_damage_bonus_percent")}%\nダメージの$v%がバリアを無視")
            "BASIC_PIERCE"->t("다음 일반 공격 ${c}회 피해 +${n("next_attack_damage_bonus_percent")}%\n방어 효과 $v% 관통","Next $c basic attacks: damage +${n("next_attack_damage_bonus_percent")}%\nPierces $v% of defenses","次の通常攻撃${c}回：ダメージ+${n("next_attack_damage_bonus_percent")}%\n防御効果を$v%貫通")
            "PIERCE","OBSERVE"->t("다음 ${c}회 피해 +${n("next_attack_damage_bonus_percent")}%\n방어 효과 $v% 관통","Next $c attacks: damage +${n("next_attack_damage_bonus_percent")}%\nPierces $v% of defenses","次の攻撃${c}回：ダメージ+${n("next_attack_damage_bonus_percent")}%\n防御効果を$v%貫通")
            "CLEANSE_ACCURACY"->t("명중 저하 1개 해제\n성공 시 다음 명중률 +6%","Removes 1 accuracy debuff\nOn success: next attack accuracy +6%","命中低下を1つ解除\n成功時、次の命中率+6%")
            "CLEANSE"->t("자신의 상태이상 1개 해제\n성공 시 다음 명중률 +6%","Removes 1 of your debuffs\nOn success: next attack accuracy +6%","自分の弱体効果を1つ解除\n成功時、次の命中率+6%")
            "REVEAL"->t("상대 은신·분신 효과 1개 해제\n성공 시 다음 명중률 +6%","Removes 1 stealth or mirror effect\nOn success: next attack accuracy +6%","相手の隠密・分身効果を1つ解除\n成功時、次の命中率+6%")
            "DISPEL","JUDGMENT","SEAL"->t("상대 강화 1개 해제\n성공 시 다음 명중률 +6%","Removes 1 enemy buff\nOn success: next attack accuracy +6%","相手の強化を1つ解除\n成功時、次の命中率+6%")
            "TAUNT"->t("상대의 다음 행동을 일반 공격으로 유도","May force the next enemy action to be a basic attack","相手の次の行動を通常攻撃に誘導します")
            "TRAP"->t("상대 공격을 1턴 늦출 수 있습니다","May delay the enemy attack by 1 turn","相手の攻撃を1ターン遅らせることがあります")
            "SLEEP"->t("상대의 준비를 끊거나 다음 행동 지연","May interrupt preparation or delay the next action","相手の準備を中断、または次の行動を遅らせます")
            "BANDAGE","LAY_HANDS","HEAL"->t("최대 HP $v% 회복\nHP ${n("cast_hp_threshold_percent")}% 이하에서 사용","Heals $v% max HP\nUsed at ${n("cast_hp_threshold_percent")}% HP or less","最大HPの$v%回復\nHP${n("cast_hp_threshold_percent")}%以下で使用")
            "REGEN"->t("다음 턴부터 최대 HP $v%씩 ${d}턴 회복","Heals $v% max HP per turn for $d turns, starting next turn","次のターンから${d}ターン、最大HPの$v%ずつ回復")
            "DAMAGE_CAP"->t("큰 피해 1회를 최대 HP $v%로 제한","Limits 1 heavy hit to $v% max HP","大きなダメージを1回、最大HPの$v%までに制限")
            "SMOKE"->t("상대 명중률 -$v% (${d}턴)","Enemy accuracy -$v% ($d turns)","相手の命中率-$v%（${d}ターン）")
            "MP_DRAIN"->t("다음 공격 ${c}회가 상대 MP $v 감소","Next $c attacks drain $v enemy MP each","次の攻撃${c}回で相手のMPを${v}ずつ減少")
            "EVASION"->t("추가 회피 $v% (${c}회, ${d}턴)\n합산 회피 최대 35%","Extra evasion $v% ($c checks, $d turns)\nCombined evasion capped at 35%","追加回避$v%（${c}回、${d}ターン）\n合計回避は最大35%")
            "POISON_COAT","BURN_PREP","EMBER"->t("다음 공격 ${c}회에 지속 피해 추가\n공격력 $v%씩 2턴","Next $c attacks add damage over time\n$v% attack per turn for 2 turns","次の攻撃${c}回に継続ダメージを追加\n2ターン、攻撃力の$v%ずつ")
            "POISON_ACCELERATE"->t("기존 독 1중첩 추가 (최대 3)\n추가된 독의 첫 피해 +${number(20+s.n("poison_first_tick")*100)}%","Adds 1 existing poison stack (max 3)\nAdded stack’s first tick deals +${number(20+s.n("poison_first_tick")*100)}%","既存の毒を1重複追加（最大3）\n追加した毒の初回ダメージ+${number(20+s.n("poison_first_tick")*100)}%")
            "HEAL_BLOCK_PREP"->t("다음 공격 ${c}회가 상대 회복량 -$v%","Next $c attacks reduce enemy healing by $v%","次の攻撃${c}回で相手の回復量-$v%")
            "AIM","BLESS","FOCUS"->t("다음 ${c}회 피해 +$v%\n명중률 +${n("accuracy_bonus_pp")}%","Next $c attacks: damage +$v%\nAccuracy +${n("accuracy_bonus_pp")}%","次の攻撃${c}回：ダメージ+$v%\n命中率+${n("accuracy_bonus_pp")}%")
            "RAPID"->t("다음 1턴 공격 ${c}회 피해 +$v%","Next $c one-turn attacks deal +$v% damage","次の1ターン攻撃${c}回のダメージ+$v%")
            "HEAL_TRACK"->t("상대가 회복한 HP의 $v%를 후속 피해로","Adds $v% of the enemy's recent healing as follow-up damage","相手の直前の回復量の$v%を追加ダメージに変換")
            "LOW_SHIELD"->t("HP 50% 이하에서 최대 HP $v% 보호막 (${d}턴)","At 50% HP or less: shields $v% max HP ($d turns)","HP50%以下で最大HPの$v%のバリア（${d}ターン）")
            "SHIELD"->t("최대 HP $v% 보호막 (${d}턴)","Shields $v% max HP ($d turns)","最大HPの$v%のバリア（${d}ターン）")
            "MIRROR"->t("분신 ${v}개 (${d}턴)\n판정마다 1개 소비","$v mirror images ($d turns)\nConsumes 1 image per check","分身${v}体（${d}ターン）\n判定ごとに1体消費")
            "STABILIZE"->t("다음 스킬 ${c}회 명중률 +$v%\n피해 +${n("skill_damage_bonus_percent")}%","Next $c skills: accuracy +$v%\nDamage +${n("skill_damage_bonus_percent")}%","次のスキル${c}回：命中率+$v%\nダメージ+${n("skill_damage_bonus_percent")}%")
            "STATUS_GUARD"->t("상태이상 ${v}회 방어 (${d}턴)","Blocks $v debuffs ($d turns)","弱体効果を${v}回防御（${d}ターン）")
            "TRUTH"->t("다음 ${c}회 상대 은신·분신 효과 -$v%","Reduces enemy stealth and mirror effects by $v% for $c attacks","次の攻撃${c}回、相手の隠密・分身効果を$v%弱化")
            "SANCTUARY"->t("상태이상 지속 1턴 단축\n제어 저항 +${n("resistance_bonus_pp")}%","Debuff duration reduced by 1 turn\nControl resistance +${n("resistance_bonus_pp")}%","弱体効果の持続を1ターン短縮\n行動妨害への耐性+${n("resistance_bonus_pp")}%")
            "LIFESTEAL"->t("다음 ${c}회 실제 피해의 $v% 회복","Heals $v% of actual HP damage for $c attacks","次の攻撃${c}回、実際に与えたHPダメージの$v%を回復")
            else->error("Missing support copy: ${s.kind}")
        }
        return if(s.support) text else t("피해 ${s.damagePercent}%","Damage ${s.damagePercent}%","ダメージ${s.damagePercent}%")+"\n"+text
    }
}
