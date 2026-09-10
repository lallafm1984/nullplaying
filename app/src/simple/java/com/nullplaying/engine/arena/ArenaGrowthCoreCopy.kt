package com.nullplaying.engine.arena

import java.util.Locale

/** Core descriptions interpolate the same replacement parameters read by the combat engine. */
internal object ArenaGrowthCoreCopy {
    fun summary(d: ArenaProgressionTraitDefinition, language: String): String {
        val key = when (d.id) {
            "AT9_WARRIOR_A_CORE" -> "replacementCastTurns"
            "AT9_WARRIOR_B_CORE" -> "replacementApplications"
            "AT9_WARRIOR_C_CORE" -> "replacementRecentHpDamagePercent"
            "AT9_ROGUE_A_CORE" -> "replacementDamageMultiplier"
            "AT9_ROGUE_B_CORE" -> "replacementLayerDurationTurns"
            "AT9_ROGUE_C_CORE" -> "replacementMaximumManaLoss"
            "AT9_RANGER_A_CORE" -> "conditionalDamageBonusPercent"
            "AT9_RANGER_B_CORE" -> "castReductionTurns"
            "AT9_RANGER_C_CORE" -> "actualHealingConversionPercent"
            "AT9_MAGE_A_CORE" -> "damageBonusPercent"
            "AT9_MAGE_B_CORE" -> "burnTotalMultiplier"
            "AT9_MAGE_C_CORE" -> "illusionCount"
            "AT9_CLERIC_A_CORE" -> "hotTickCount"
            "AT9_CLERIC_B_CORE" -> "actualHpDamageHealPercent"
            "AT9_CLERIC_C_CORE" -> "validTurns"
            "AT9_PALADIN_A_CORE" -> "preventedDamageToExtraPercent"
            "AT9_PALADIN_B_CORE" -> "replacementExecutionMpCost"
            "AT9_PALADIN_C_CORE" -> "maximumShieldPercentOwnMaxHp"
            else -> error("Unknown core ${d.id}")
        }
        val n = String.format(Locale.ROOT, "%.2f", d.coreParameters.getValue(key)).trimEnd('0').trimEnd('.')
        val ko = language == "ko"; val ja = language == "ja"
        return when (key) {
            "replacementCastTurns" -> if (ko) "연결 공격 ${n}턴 시전" else if (ja) "連携攻撃を${n}ターンに" else "Chain attack: $n-turn cast"
            "replacementApplications" -> if (ko) "추가 파쇄 ${n}회" else if (ja) "追加破砕${n}回" else "$n shatter applications"
            "replacementRecentHpDamagePercent" -> if (ko) "받은 HP 피해 ${n}% 반격" else if (ja) "受けたHP被害の${n}%を反撃" else "Counter: $n% of received HP damage"
            "preventedDamageToExtraPercent" -> if (ko) "막은 HP 피해 ${n}% 응징" else if (ja) "防いだHP被害の${n}%を応報" else "Retribution: $n% of prevented HP damage"
            "replacementDamageMultiplier" -> if (ko) "긴 기습 ${n}배·시전 단축" else if (ja) "長い奇襲${n}倍・詠唱短縮" else "Long ambush: ${n}×, shorter cast"
            "replacementLayerDurationTurns" -> if (ko) "독 ${n}턴 지속" else if (ja) "毒が${n}ターン持続" else "Poison lasts $n turns"
            "replacementMaximumManaLoss" -> if (ko) "상대 MP 최대 ${n} 감소" else if (ja) "相手MP最大${n}減少" else "Drain up to $n enemy MP"
            "conditionalDamageBonusPercent", "damageBonusPercent" -> if (ko) "적격 공격 피해 +${n}%" else if (ja) "条件を満たす攻撃+${n}%" else "Eligible attack damage +$n%"
            "castReductionTurns" -> if (ko) "실제 해제 후 시전 −${n}턴" else if (ja) "実際の解除後、詠唱−${n}ターン" else "After dispel: cast −$n turn"
            "actualHealingConversionPercent" -> if (ko) "실회복 ${n}%를 추적 피해로" else if (ja) "実回復の${n}%を追跡ダメージに" else "$n% actual healing as pursuit damage"
            "burnTotalMultiplier" -> if (ko) "화상 총량 ${n}배" else if (ja) "火傷の総量${n}倍" else "Total burn ${n}×"
            "illusionCount" -> if (ko) "빠른 분신 ${n}개" else if (ja) "素早い分身${n}体" else "$n quick-cast images"
            "hotTickCount" -> if (ko) "즉시 회복 + ${n}회 재생" else if (ja) "即時回復＋${n}回の再生" else "Immediate heal + $n regeneration ticks"
            "actualHpDamageHealPercent" -> if (ko) "실제 HP 피해 ${n}% 회복" else if (ja) "実HPダメージの${n}%回復" else "Heal $n% of actual HP damage"
            "validTurns" -> if (ko) "해제한 상태 ${n}턴 방어" else if (ja) "解除した状態を${n}ターン防御" else "Guard cleansed status for $n turns"
            "replacementExecutionMpCost" -> if (ko) "실제 해제 후 선고 ${n}MP" else if (ja) "実際の解除後、宣告${n}MP" else "After dispel: Sentence of Execution $n MP"
            else -> if (ko) "안수→최대 HP ${n}% 보호막" else if (ja) "按手→最大HP${n}%シールド" else "Lay on Hands → $n% max HP shield"
        }
    }

    fun condition(d: ArenaProgressionTraitDefinition, language: String): String = if (language == "ja")
        "連動する補助スキルを実際に使用し、下記の条件を満たす時。"
        else "Use the linked owned support skill and meet the conditions below."

    fun effect(d: ArenaProgressionTraitDefinition, language: String): String {
        fun n(key: String) = String.format(Locale.ROOT, "%.3f", d.coreParameters.getValue(key)).trimEnd('0').trimEnd('.')
        val ja = language == "ja"
        if (language == "ko") return when (d.id) {
            "AT9_WARRIOR_A_CORE" -> "전투 함성의 직접 피해 증가를 ${n("replacementShoutDamageBonusPercent")}%로 대체합니다. 함성 중 평타 HP 명중 ${n("requiredBasicHpHits")}회 후 다음 ${n("eligibleOriginalCastTurns")}턴 공격기를 ${n("replacementCastTurns")}턴·원래 위력의 ${n("attackPowerMultiplier")}배로 바꿉니다."
            "AT9_WARRIOR_B_CORE" -> "파쇄 준비를 성공 평타 ${n("replacementApplications")}회로 바꾸고, 각 추가 파쇄량을 원래의 ${n("perApplicationShatterMultiplier")}배로 낮춥니다. 유효 기간 ${n("durationTurns")}턴."
            "AT9_WARRIOR_C_CORE" -> "반격 준비의 고정 피해 증가를 없앱니다. 시작 시 같은 턴 또는 직전 턴에 받은 실제 직접 HP 피해의 ${n("replacementRecentHpDamagePercent")}%를 다음 평타에 더합니다. 자기 최대 HP ${n("maximumExtraPercentOwnMaxHp")}% 상한."
            "AT9_ROGUE_A_CORE" -> "은신을 원래 2~3턴 공격기 전용으로 바꿉니다. 시작 시 사용권을 소비해 시전을 ${n("castTurnsReduction")}턴 줄이고 피해를 ${n("replacementDamageMultiplier")}배로 만듭니다. 최소 ${n("minimumCastTurns")}턴. 평타·원래 1턴 공격기는 소비하지 않습니다."
            "AT9_ROGUE_B_CORE" -> "새 독의 기간을 ${n("replacementLayerDurationTurns")}턴, 틱 피해를 원래의 ${n("tickDamageMultiplier")}배로 바꿉니다. 최대 ${n("maximumStacks")}층이며 기존 층은 갱신하지 않습니다."
            "AT9_ROGUE_C_CORE" -> "교란 준비의 상대 MP 감소 상한을 ${n("replacementMaximumManaLoss")}으로 높이고 전달 공격의 직접 피해를 원래의 ${n("deliveryDirectDamageMultiplier")}배로 낮춥니다. 상대 잔액 이상을 빼거나 자신의 MP로 가져오지 않습니다."
            "AT9_RANGER_A_CORE" -> "정조준의 상시 피해 증가를 없애고 명중 +${n("retainedAccuracyPercentagePoints")}%p는 유지합니다. 공개된 상대 긴 공격의 잔여 ${n("requiredObservedEnemyRemainingTurns")}턴을 보고 시작한 원래 ${n("requiredOwnOriginalCastTurns")}턴 공격기만 피해 +${n("conditionalDamageBonusPercent")}%·추가 ${n("additionalAttackMp")}MP."
            "AT9_RANGER_B_CORE" -> "간파 비용을 ${n("sourceSupportMp")}MP로 바꿉니다. 은신·미러를 실제 제거하면 ${n("validTurns")}턴 안의 다음 원래 2~3턴 공격기 시전을 ${n("castReductionTurns")}턴 줄입니다. 최소 ${n("minimumCastTurns")}턴."
            "AT9_RANGER_C_CORE" -> "회복 추적을 ${n("sourceSupportMp")}MP로 바꿉니다. 같은 턴·직전 턴의 미사용 실제 회복 합계 ${n("actualHealingConversionPercent")}%를 다음 성공 직접 공격에 더합니다. 상대 최대 HP ${n("targetMaxHpCapPercent")}% 상한."
            "AT9_MAGE_A_CORE" -> "마력 응축을 원래 ${n("requiredOriginalAttackCastTurns")}턴 공격기 전용으로 바꿉니다. 직접 피해 +${n("damageBonusPercent")}%·공격 시작 시 추가 ${n("additionalAttackMp")}MP. ${n("validTurns")}턴 안에 ${n("uses")}회 소비합니다."
            "AT9_MAGE_B_CORE" -> "화염 각인을 전달하는 공격의 직접 피해를 ${n("directDamageMultiplier")}배로 낮추고 ${n("burnTickCount")}회 화상의 총량을 원래의 ${n("burnTotalMultiplier")}배로 높입니다."
            "AT9_MAGE_C_CORE" -> "미러이미지는 시전 ${n("sourceCastTurns")}턴·${n("sourceSupportMp")}MP, 분신 ${n("illusionCount")}개·최대 ${n("validTurns")}턴입니다. 적격 명중 무효 확률은 차례로 1/3, 1/2이며 각 판정에서 분신을 소비합니다."
            "AT9_CLERIC_A_CORE" -> "회복을 즉시 최대 HP ${n("instantHealMaxHpPercent")}%와 다음 ${n("hotTickCount")}턴 시작마다 ${n("hotHealMaxHpPercentPerTick")}%로 나눕니다. 합계 ${n("totalHealMaxHpPercent")}%·${n("sourceSupportMp")}MP."
            "AT9_CLERIC_B_CORE" -> "생명의 연결을 ${n("validTurns")}턴 안의 첫 보유 공격기 실제 HP 명중 ${n("uses")}회에만 적용합니다. 실제 HP 피해의 ${n("actualHpDamageHealPercent")}% 회복, 자기 최대 HP ${n("ownMaxHpHealCapPercent")}% 상한."
            "AT9_CLERIC_C_CORE" -> "정화 비용을 ${n("sourceSupportMp")}MP로 바꿉니다. 실제 제거한 상태와 같은 종류의 새 상태를 ${n("validTurns")}턴 안에 ${n("uses")}회 막습니다. 독은 다음 ${n("poisonLayersBlocked")}층만 막습니다."
            "AT9_PALADIN_A_CORE" -> "수호 서약의 공격기 피해 감소를 ${n("replacementSkillDamageReductionPercent")}%로 바꿉니다. 실제 줄인 피해의 ${n("preventedDamageToExtraPercent")}%를 ${n("followupWindowTurns")}턴 안의 다음 보유 공격기에 더합니다. 자기 최대 HP ${n("maximumExtraPercentOwnMaxHp")}% 상한."
            "AT9_PALADIN_B_CORE" -> "신성 판결로 강화를 실제 제거하면 ${n("followupWindowTurns")}턴 안의 다음 집행 선고는 상대 저체력 조건을 면제하고 ${n("replacementExecutionMpCost")}MP로 바꿉니다. 원래 다음 공격기 +${n("existingExecutionDamageBonusPercent")}%만 유지합니다."
            "AT9_PALADIN_C_CORE" -> "안수의 회복을 같은 산정량의 ${n("shieldDurationTurns")}턴 보호막으로 바꿉니다. 자기 최대 HP ${n("maximumShieldPercentOwnMaxHp")}% 상한·시전 ${n("castTurns")}턴·${n("originalMpCost")}MP·결투당 ${n("usesPerDuel")}회. 실제 HP는 늘지 않습니다."
            else -> error("Unknown core ${d.id}")
        }
        return when (d.id) {
            "AT9_WARRIOR_A_CORE" -> if (ja) "戦いの雄叫びの直接ダメージ補正を+${n("replacementShoutDamageBonusPercent")}%に変更。戦いの雄叫び中に通常攻撃がHPへ${n("requiredBasicHpHits")}回命中すると、次の元2ターン攻撃を${n("replacementCastTurns")}ターン・元威力の${n("attackPowerMultiplier")}倍に変更。" else "Battle Cry gives +${n("replacementShoutDamageBonusPercent")}% direct damage. After ${n("requiredBasicHpHits")} basic HP hits during that cry, the next originally 2-turn attack takes ${n("replacementCastTurns")} turn at ${n("attackPowerMultiplier")}× original power."
            "AT9_WARRIOR_B_CORE" -> if (ja) "破砕の構えを${n("replacementApplications")}回に変更。各回の追加破砕量は元の${n("perApplicationShatterMultiplier")}倍、期間${n("durationTurns")}ターン。" else "Shatter Preparation applies ${n("replacementApplications")} times, each at ${n("perApplicationShatterMultiplier")}× original extra shatter, within ${n("durationTurns")} turns."
            "AT9_WARRIOR_C_CORE" -> if (ja) "反撃の構えの固定ダメージ補正を撤去。開始時に同じターンか前のターンの直接HP被害を記録し、その${n("replacementRecentHpDamagePercent")}%を次の通常攻撃に加算。上限は自分の最大HPの${n("maximumExtraPercentOwnMaxHp")}%。" else "Counter Preparation replaces its flat bonus with ${n("replacementRecentHpDamagePercent")}% of one actual direct hit received this turn or last turn and captured at start. Extra damage caps at ${n("maximumExtraPercentOwnMaxHp")}% of your max HP."
            "AT9_ROGUE_A_CORE" -> if (ja) "隠密を元2～3ターンの攻撃専用に変更。開始時に使用権を消費し、詠唱を${n("castTurnsReduction")}ターン短縮、直接ダメージを${n("replacementDamageMultiplier")}倍にする。通常攻撃・元1ターン攻撃は消費対象外。" else "Stealth applies only to originally 2–3-turn attacks. Consume it at cast start to shorten casting by ${n("castTurnsReduction")} turn and deal ${n("replacementDamageMultiplier")}× direct damage. Basic and originally 1-turn attacks do not consume it."
            "AT9_ROGUE_B_CORE" -> if (ja) "新しい毒の期間を${n("replacementLayerDurationTurns")}ターン、各ダメージを元の${n("tickDamageMultiplier")}倍に変更。最大${n("maximumStacks")}層で既存の毒は延長しない。" else "New poison layers last ${n("replacementLayerDurationTurns")} turns at ${n("tickDamageMultiplier")}× damage per tick. Maximum ${n("maximumStacks")} layers; existing layers never refresh."
            "AT9_ROGUE_C_CORE" -> if (ja) "妨害準備のMP減少上限を${n("replacementMaximumManaLoss")}に変更。効果を届ける直接攻撃は元の${n("deliveryDirectDamageMultiplier")}倍。敵の残高以上は奪わず、自分のMPにはならない。" else "Disruption Preparation removes up to ${n("replacementMaximumManaLoss")} enemy MP; the delivery attack deals ${n("deliveryDirectDamageMultiplier")}× direct damage. Never removes more than the current balance or restores your MP."
            "AT9_RANGER_A_CORE" -> if (ja) "精密照準の常時ダメージ補正を撤去し、命中+${n("retainedAccuracyPercentagePoints")}ポイントを維持。敵の元2～3ターン攻撃の残り1ターンを確認して始めた元1ターン攻撃だけ、ダメージ+${n("conditionalDamageBonusPercent")}%・追加${n("additionalAttackMp")}MP。" else "Aim keeps +${n("retainedAccuracyPercentagePoints")} pp accuracy and loses its continuous damage bonus. An originally 1-turn attack begun after observing an enemy long cast with 1 turn left gains +${n("conditionalDamageBonusPercent")}% damage and costs ${n("additionalAttackMp")} extra MP."
            "AT9_RANGER_B_CORE" -> if (ja) "看破の費用を${n("sourceSupportMp")}MPに変更。隠密または分身を実際に解除した後、${n("validTurns")}ターン以内の次の元2～3ターン攻撃を${n("castReductionTurns")}ターン短縮。" else "Expose costs ${n("sourceSupportMp")} MP. After actually removing Stealth or Mirror Images, shorten the next originally 2–3-turn attack within ${n("validTurns")} turns by ${n("castReductionTurns")} turn."
            "AT9_RANGER_C_CORE" -> if (ja) "回復追跡を${n("sourceSupportMp")}MPに変更。同じターンと前のターンの未使用の実回復を記録し、合計の${n("actualHealingConversionPercent")}%を次の成功直接攻撃に加算。上限は敵の最大HPの${n("targetMaxHpCapPercent")}%。" else "Track Recovery costs ${n("sourceSupportMp")} MP and captures unused actual healing this turn and last turn. The next successful direct attack adds ${n("actualHealingConversionPercent")}% of that total, capped at ${n("targetMaxHpCapPercent")}% of enemy max HP."
            "AT9_MAGE_A_CORE" -> if (ja) "魔力凝縮を元${n("requiredOriginalAttackCastTurns")}ターンの攻撃専用に変更。ダメージ+${n("damageBonusPercent")}%、攻撃開始時に追加${n("additionalAttackMp")}MP。${n("validTurns")}ターン以内に1回消費。" else "Mana Focus applies only to originally ${n("requiredOriginalAttackCastTurns")}-turn attacks: +${n("damageBonusPercent")}% direct damage and ${n("additionalAttackMp")} extra MP at attack start. Consume once within ${n("validTurns")} turns."
            "AT9_MAGE_B_CORE" -> if (ja) "火炎刻印を届ける攻撃の直接ダメージを元の${n("directDamageMultiplier")}倍、${n("burnTickCount")}回の火傷合計を元の${n("burnTotalMultiplier")}倍に変更。" else "The attack delivering Flame Inscription deals ${n("directDamageMultiplier")}× direct damage; total burn over ${n("burnTickCount")} ticks becomes ${n("burnTotalMultiplier")}× the original."
            "AT9_MAGE_C_CORE" -> if (ja) "ミラーイメージは${n("sourceCastTurns")}ターン・${n("sourceSupportMp")}MP、分身${n("illusionCount")}体、最長${n("validTurns")}ターン。適格な命中を無効化する確率は順に1/3、1/2。各判定で1体消費。" else "Mirror Image takes ${n("sourceCastTurns")} turn and ${n("sourceSupportMp")} MP, with ${n("illusionCount")} images lasting at most ${n("validTurns")} turns. The next eligible hit checks nullification at 1/3, then 1/2; each check consumes one image."
            "AT9_CLERIC_A_CORE" -> if (ja) "回復を即時最大HP${n("instantHealMaxHpPercent")}%と、その後${n("hotTickCount")}ターン開始時に各${n("hotHealMaxHpPercentPerTick")}%へ分割。合計${n("totalHealMaxHpPercent")}%、費用${n("sourceSupportMp")}MP。" else "Heal restores ${n("instantHealMaxHpPercent")}% of max HP immediately, then ${n("hotHealMaxHpPercentPerTick")}% at each of the next ${n("hotTickCount")} turn starts. Total ${n("totalHealMaxHpPercent")}%; cost ${n("sourceSupportMp")} MP."
            "AT9_CLERIC_B_CORE" -> if (ja) "命のつながりを${n("validTurns")}ターン以内の最初の所持攻撃スキルの実HP命中1回に限定。その実被害の${n("actualHpDamageHealPercent")}%を回復し、上限は自分の最大HPの${n("ownMaxHpHealCapPercent")}%。" else "Life Link applies once to the first owned attack skill that deals actual HP damage within ${n("validTurns")} turns. Heal ${n("actualHpDamageHealPercent")}% of actual HP damage, capped at ${n("ownMaxHpHealCapPercent")}% of your max HP."
            "AT9_CLERIC_C_CORE" -> if (ja) "浄化を${n("sourceSupportMp")}MPに変更。実際に解除したものと同種の新状態を${n("validTurns")}ターン以内に${n("uses")}回防ぐ。毒は次の${n("poisonLayersBlocked")}層のみ。" else "Purify costs ${n("sourceSupportMp")} MP. After an actual removal, block ${n("uses")} new application of that same status kind within ${n("validTurns")} turns. Poison protection blocks only the next ${n("poisonLayersBlocked")} layer."
            "AT9_PALADIN_A_CORE" -> if (ja) "守護の誓約の軽減を${n("replacementSkillDamageReductionPercent")}%に変更。実際に軽減した量の${n("preventedDamageToExtraPercent")}%を${n("followupWindowTurns")}ターン以内の次の所持攻撃に加算。上限は自分の最大HPの${n("maximumExtraPercentOwnMaxHp")}%。" else "Guardian's Vow reduces skill damage by ${n("replacementSkillDamageReductionPercent")}% instead. Add ${n("preventedDamageToExtraPercent")}% of actual prevented damage to the next owned attack within ${n("followupWindowTurns")} turns, capped at ${n("maximumExtraPercentOwnMaxHp")}% of your max HP."
            "AT9_PALADIN_B_CORE" -> if (ja) "神聖な裁きで実際に強化を解除すると、${n("followupWindowTurns")}ターン以内の次の執行宣告は敵の低HP条件を免除し、費用${n("replacementExecutionMpCost")}MP。元の次の攻撃+${n("existingExecutionDamageBonusPercent")}%を維持。" else "After Divine Judgment actually dispels a buff, the next Sentence of Execution within ${n("followupWindowTurns")} turns waives the low-enemy-HP requirement and costs ${n("replacementExecutionMpCost")} MP. Its existing +${n("existingExecutionDamageBonusPercent")}% next-attack bonus stays unchanged."
            "AT9_PALADIN_C_CORE" -> if (ja) "按手の回復を同じ算定量の${n("shieldDurationTurns")}ターンシールドへ変更。上限は自分の最大HPの${n("maximumShieldPercentOwnMaxHp")}%。${n("castTurns")}ターン・${n("originalMpCost")}MP・対戦中${n("usesPerDuel")}回。HPは回復しない。" else "Lay on Hands grants a ${n("shieldDurationTurns")}-turn shield instead of healing, using the same calculated amount capped at ${n("maximumShieldPercentOwnMaxHp")}% of max HP. ${n("castTurns")}-turn cast, ${n("originalMpCost")} MP, ${n("usesPerDuel")} use per duel; restores no HP."
            else -> error("Unknown core ${d.id}")
        }
    }
    fun limitation(d: ArenaProgressionTraitDefinition, language: String): String = if (language == "ja")
        "核心は全分岐で1つ、基本5ポイント、追加強化なし。元の使用権と有効期間を守り、追加行動やMP返還はありません。詠唱は最低1ターン。HP0後は発動しません。"
        else "One core across all branches, costing 5 base points; no enhancement. Existing charges and expiry remain finite. No extra actions or MP refunds. Casting takes at least 1 turn; no activation after HP reaches zero."
}
