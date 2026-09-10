package com.nullplaying.engine

import com.nullplaying.model.BATTLE_MAX_ACTIVE_TRAITS
import com.nullplaying.model.BATTLE_TRAIT_REMOVAL_MILLIS
import com.nullplaying.model.ActiveBattleTrait
import com.nullplaying.model.BattleActionKind
import com.nullplaying.model.BattleOutcome
import com.nullplaying.model.BattleSide
import com.nullplaying.model.BattleTraitCategory
import com.nullplaying.model.BattleTraitCombatProfile
import com.nullplaying.model.BattleTraitDefinition
import com.nullplaying.model.BattleTraitMutation
import com.nullplaying.model.BattleTraitMutationStatus
import com.nullplaying.model.BattleTraitRemoval
import com.nullplaying.model.BattleTraitState
import com.nullplaying.model.ProjectionBattleResult

/**
 * Trait identity, lifecycle and normalized V2 combat style. The six axes always redistribute a
 * fixed total, while the trusted projection engine remains the sole owner of combat outcomes.
 */
object BattleTraitCatalog {
    val all: List<BattleTraitDefinition> = listOf(
        trait("TRAIT_001", "선봉의 발걸음", "전투가 열리자마자 망설임 없이 거리를 좁힌다.", BattleTraitCategory.OPENING),
        trait("TRAIT_002", "매복을 깨는 눈", "첫 움직임에서 상대가 숨긴 의도를 빠르게 알아챈다.", BattleTraitCategory.OPENING),
        trait("TRAIT_003", "숨죽인 개전", "고요히 기회를 기다렸다가 예고 없이 승부를 연다.", BattleTraitCategory.OPENING),
        trait("TRAIT_004", "첫 칼의 주인", "첫 합의 주도권을 자신의 무기 끝에 붙잡아 둔다.", BattleTraitCategory.OPENING),
        trait("TRAIT_005", "거리 재는 사냥꾼", "초반의 짧은 탐색으로 가장 유리한 간격을 찾는다.", BattleTraitCategory.OPENING),
        trait("TRAIT_006", "기선을 빼앗는 자", "상대가 자세를 갖추기 전에 전장의 호흡을 가져온다.", BattleTraitCategory.OPENING),
        trait("TRAIT_007", "느린 불씨", "서두르지 않는 첫 합으로 뒤의 폭발을 준비한다.", BattleTraitCategory.OPENING),
        trait("TRAIT_008", "반걸음의 계산", "작은 위치 변화만으로 첫 공방의 답을 만들어 낸다.", BattleTraitCategory.OPENING),
        trait("TRAIT_009", "정면승부가", "개전과 동시에 물러설 뜻이 없음을 분명히 보인다.", BattleTraitCategory.OPENING),
        trait("TRAIT_010", "침묵의 도전자", "말보다 자세로 결투의 시작을 알리는 편이다.", BattleTraitCategory.OPENING),

        trait("TRAIT_011", "연격의 폭풍", "한 번 잡은 공격 기회를 여러 타격으로 이어 간다.", BattleTraitCategory.OFFENSE),
        trait("TRAIT_012", "빈틈 추적자", "작게 벌어진 틈도 놓치지 않고 다음 공격으로 연결한다.", BattleTraitCategory.OFFENSE),
        trait("TRAIT_013", "갑주 파쇄자", "단단한 수비를 상대로도 정면에서 균열을 만든다.", BattleTraitCategory.OFFENSE),
        trait("TRAIT_014", "마력 폭주", "억눌렀던 마력을 짧은 순간에 거세게 분출한다.", BattleTraitCategory.OFFENSE),
        trait("TRAIT_015", "끊임없는 압박", "상대가 자세를 고칠 틈 없이 공격을 겹쳐 놓는다.", BattleTraitCategory.OFFENSE),
        trait("TRAIT_016", "돌진 본능", "위험을 감수하고서라도 공격 거리를 먼저 지운다.", BattleTraitCategory.OFFENSE),
        trait("TRAIT_017", "치명점 사냥꾼", "결정적인 상처가 될 지점을 집요하게 노린다.", BattleTraitCategory.OFFENSE),
        trait("TRAIT_018", "기술 연계자", "서로 다른 기술을 자연스러운 하나의 공세로 엮는다.", BattleTraitCategory.OFFENSE),
        trait("TRAIT_019", "일격 집중", "여러 가능성을 버리고 가장 무거운 한 번에 힘을 모은다.", BattleTraitCategory.OFFENSE),
        trait("TRAIT_020", "후퇴 없는 칼날", "공격을 시작한 뒤에는 좀처럼 뒤로 물러나지 않는다.", BattleTraitCategory.OFFENSE),

        trait("TRAIT_021", "철벽", "거센 충격 앞에서도 중심을 잃지 않고 자리를 지킨다.", BattleTraitCategory.DEFENSE),
        trait("TRAIT_022", "방패 뒤의 눈", "막아 내는 동안에도 상대의 다음 수를 세밀히 살핀다.", BattleTraitCategory.DEFENSE),
        trait("TRAIT_023", "충격 흘리기", "힘을 정면으로 받지 않고 비껴 보내 피해를 줄인다.", BattleTraitCategory.DEFENSE),
        trait("TRAIT_024", "주문 봉쇄자", "마력이 완성되기 전 흐름을 끊어 위력을 낮춘다.", BattleTraitCategory.DEFENSE),
        trait("TRAIT_025", "마지막 방벽", "위태로운 순간일수록 더 단단한 수비를 세운다.", BattleTraitCategory.DEFENSE),
        trait("TRAIT_026", "피해 감수자", "피할 수 없는 상처를 받아들이고 더 큰 위험을 막는다.", BattleTraitCategory.DEFENSE),
        trait("TRAIT_027", "빈틈없는 수비", "방어 자세를 바꾸는 순간에도 틈을 거의 보이지 않는다.", BattleTraitCategory.DEFENSE),
        trait("TRAIT_028", "반격의 자세", "막는 동작 속에 곧바로 되돌려 줄 힘을 남겨 둔다.", BattleTraitCategory.DEFENSE),
        trait("TRAIT_029", "버티는 뿌리", "전장이 흔들려도 뿌리내린 듯 자세를 유지한다.", BattleTraitCategory.DEFENSE),
        trait("TRAIT_030", "침착한 수호자", "다급한 상황에서도 지킬 것과 버릴 것을 구분한다.", BattleTraitCategory.DEFENSE),

        trait("TRAIT_031", "역전의 불씨", "꺼져 가던 승산에서 작은 반전의 기회를 살려 낸다.", BattleTraitCategory.REVERSAL),
        trait("TRAIT_032", "벼랑 끝의 반격", "더 물러설 곳이 없을 때 가장 날카롭게 되받아친다.", BattleTraitCategory.REVERSAL),
        trait("TRAIT_033", "흐름을 뒤집는 자", "상대에게 넘어간 주도권을 한 장면으로 되찾는다.", BattleTraitCategory.REVERSAL),
        trait("TRAIT_034", "패색을 지우는 칼", "불리한 전황을 인정하지 않고 새로운 결말을 벤다.", BattleTraitCategory.REVERSAL),
        trait("TRAIT_035", "마지막 기회", "단 한 번 남은 가능성을 놓치지 않는 집중력을 보인다.", BattleTraitCategory.REVERSAL),
        trait("TRAIT_036", "상처 입은 맹수", "상처가 깊어질수록 반격은 오히려 거칠어진다.", BattleTraitCategory.REVERSAL),
        trait("TRAIT_037", "불리함의 지배자", "열세 속에서 상대의 방심과 조급함을 이용한다.", BattleTraitCategory.REVERSAL),
        trait("TRAIT_038", "되받아치는 파도", "밀려난 힘을 모아 더 큰 흐름으로 되돌려 준다.", BattleTraitCategory.REVERSAL),
        trait("TRAIT_039", "기사회생", "쓰러지기 직전 새로운 호흡과 움직임을 찾아낸다.", BattleTraitCategory.REVERSAL),
        trait("TRAIT_040", "끝나지 않은 승부", "결말이 확정되기 전까지 패배를 받아들이지 않는다.", BattleTraitCategory.REVERSAL),

        trait("TRAIT_041", "장기전 전문가", "합이 길어질수록 상대보다 안정된 판단을 보여 준다.", BattleTraitCategory.ENDURANCE),
        trait("TRAIT_042", "지치지 않는 발", "긴 공방 뒤에도 처음과 비슷한 보폭을 유지한다.", BattleTraitCategory.ENDURANCE),
        trait("TRAIT_043", "고통을 견디는 자", "누적된 상처 속에서도 해야 할 행동을 이어 간다.", BattleTraitCategory.ENDURANCE),
        trait("TRAIT_044", "여덟 합의 생존자", "마지막 합까지 집중을 잃지 않고 전장에 남는다.", BattleTraitCategory.ENDURANCE),
        trait("TRAIT_045", "느리지만 단단한 자", "빠르지는 않아도 매 순간 흔들림 없이 전진한다.", BattleTraitCategory.ENDURANCE),
        trait("TRAIT_046", "소모전의 지배자", "서로의 힘이 줄어드는 과정 자체를 유리하게 만든다.", BattleTraitCategory.ENDURANCE),
        trait("TRAIT_047", "꺼지지 않는 호흡", "위기와 회복을 거치며 전투의 리듬을 오래 유지한다.", BattleTraitCategory.ENDURANCE),
        trait("TRAIT_048", "철의 인내", "즉각적인 성과보다 끝까지 버티는 선택을 믿는다.", BattleTraitCategory.ENDURANCE),
        trait("TRAIT_049", "끈질긴 추격자", "거리가 몇 번 벌어져도 상대를 시야에서 놓치지 않는다.", BattleTraitCategory.ENDURANCE),
        trait("TRAIT_050", "마지막까지 선 자", "승패가 갈리는 순간까지 자신의 두 발로 버틴다.", BattleTraitCategory.ENDURANCE),

        trait("TRAIT_051", "정확한 칼끝", "불필요한 움직임 없이 노린 지점에 공격을 모은다.", BattleTraitCategory.PRECISION),
        trait("TRAIT_052", "급소 관찰자", "공방 중 드러난 약점을 기억해 정확히 되짚는다.", BattleTraitCategory.PRECISION),
        trait("TRAIT_053", "한 치의 계산", "거리와 속도를 세밀하게 맞춰 공격의 오차를 줄인다.", BattleTraitCategory.PRECISION),
        trait("TRAIT_054", "마력 조율자", "마력의 양과 발동 시점을 섬세하게 조절한다.", BattleTraitCategory.PRECISION),
        trait("TRAIT_055", "흔들림 없는 손", "압박을 받는 순간에도 무기와 주문의 궤적이 안정적이다.", BattleTraitCategory.PRECISION),
        trait("TRAIT_056", "낭비 없는 기술", "필요한 만큼의 힘만 사용해 다음 행동을 남겨 둔다.", BattleTraitCategory.PRECISION),
        trait("TRAIT_057", "빈틈없는 조준", "시야가 흐트러져도 목표의 중심을 놓치지 않는다.", BattleTraitCategory.PRECISION),
        trait("TRAIT_058", "정교한 파괴자", "거친 힘보다 정확한 파괴 순서로 수비를 무너뜨린다.", BattleTraitCategory.PRECISION),
        trait("TRAIT_059", "때를 아는 자", "서두르지 않고 가장 성공 가능성이 높은 순간을 고른다.", BattleTraitCategory.PRECISION),
        trait("TRAIT_060", "한 번의 정답", "수많은 선택지 가운데 승부를 가를 하나를 골라낸다.", BattleTraitCategory.PRECISION),

        trait("TRAIT_061", "전장 독해자", "상대와 지형의 변화를 하나의 흐름으로 읽는다.", BattleTraitCategory.TACTICS),
        trait("TRAIT_062", "지침의 실천자", "정한 결투장 지침을 상황에 맞게 끝까지 구현한다.", BattleTraitCategory.TACTICS),
        trait("TRAIT_063", "수읽기의 달인", "한 수 뒤가 아니라 여러 교환 뒤의 장면을 예상한다.", BattleTraitCategory.TACTICS),
        trait("TRAIT_064", "거리를 지배하는 자", "접근과 이탈의 시점을 조절해 원하는 공방을 만든다.", BattleTraitCategory.TACTICS),
        trait("TRAIT_065", "기술을 아끼는 자", "강한 기술을 성급히 쓰지 않고 결정적인 때까지 보존한다.", BattleTraitCategory.TACTICS),
        trait("TRAIT_066", "유인하는 그림자", "약점을 보인 듯 움직여 상대를 준비한 자리로 끌어들인다.", BattleTraitCategory.TACTICS),
        trait("TRAIT_067", "리듬 파괴자", "예상 밖의 간격으로 상대의 익숙한 전투 박자를 끊는다.", BattleTraitCategory.TACTICS),
        trait("TRAIT_068", "상성을 읽는 눈", "직업과 기술의 맞물림을 빠르게 파악해 대응한다.", BattleTraitCategory.TACTICS),
        trait("TRAIT_069", "변칙 전술가", "정석에서 벗어난 행동을 필요한 순간에만 꺼내 든다.", BattleTraitCategory.TACTICS),
        trait("TRAIT_070", "흐름 설계자", "각 행동을 다음 장면의 유리함으로 이어지게 만든다.", BattleTraitCategory.TACTICS),

        trait("TRAIT_071", "냉정한 심장", "승부가 거칠어져도 감정보다 판단을 앞세운다.", BattleTraitCategory.TEMPERAMENT),
        trait("TRAIT_072", "뜨거운 투지", "불리한 순간에도 기세와 열정을 잃지 않는다.", BattleTraitCategory.TEMPERAMENT),
        trait("TRAIT_073", "고요한 집중", "주변의 소란을 지우고 눈앞의 공방에 몰입한다.", BattleTraitCategory.TEMPERAMENT),
        trait("TRAIT_074", "승부의 즐거움", "강한 상대와 맞서는 순간에 오히려 생기를 얻는다.", BattleTraitCategory.TEMPERAMENT),
        trait("TRAIT_075", "두려움 없는 자", "상대의 명성과 힘에 행동이 위축되지 않는다.", BattleTraitCategory.TEMPERAMENT),
        trait("TRAIT_076", "신중한 도전자", "용기를 잃지 않으면서도 위험을 가볍게 보지 않는다.", BattleTraitCategory.TEMPERAMENT),
        trait("TRAIT_077", "자존심 높은 검", "자신의 방식과 이름에 걸맞은 승부를 고집한다.", BattleTraitCategory.TEMPERAMENT),
        trait("TRAIT_078", "흔들리지 않는 의지", "예상 밖의 전개에도 처음 세운 목표를 놓치지 않는다.", BattleTraitCategory.TEMPERAMENT),
        trait("TRAIT_079", "호승심", "승부의 기미가 보이면 한 걸음 더 깊이 들어간다.", BattleTraitCategory.TEMPERAMENT),
        trait("TRAIT_080", "침묵의 집념", "드러내지 않아도 포기하지 않는 마음이 행동에 남는다.", BattleTraitCategory.TEMPERAMENT),

        trait("TRAIT_081", "연승의 기세", "좋은 흐름을 다음 공방까지 자연스럽게 이어 간다.", BattleTraitCategory.MOMENTUM),
        trait("TRAIT_082", "몰아치는 파도", "우세를 잡은 순간 공격의 파도를 연달아 밀어 넣는다.", BattleTraitCategory.MOMENTUM),
        trait("TRAIT_083", "승기를 놓치지 않는 자", "기울기 시작한 전세를 다시 평평하게 두지 않는다.", BattleTraitCategory.MOMENTUM),
        trait("TRAIT_084", "전세 장악자", "작은 성공을 전장 전체의 주도권으로 확장한다.", BattleTraitCategory.MOMENTUM),
        trait("TRAIT_085", "압박의 고삐", "빠르게 몰아붙이면서도 공세의 방향을 통제한다.", BattleTraitCategory.MOMENTUM),
        trait("TRAIT_086", "흐름을 탄 칼날", "한번 열린 길을 따라 끊김 없이 다음 공격을 보낸다.", BattleTraitCategory.MOMENTUM),
        trait("TRAIT_087", "박자를 빼앗는 자", "연속 행동으로 상대가 자신의 리듬을 되찾지 못하게 한다.", BattleTraitCategory.MOMENTUM),
        trait("TRAIT_088", "주도권의 화신", "전투의 속도와 방향을 자신의 판단 아래 둔다.", BattleTraitCategory.MOMENTUM),
        trait("TRAIT_089", "연속 기술가", "기술의 끝을 다음 기술의 시작으로 매끄럽게 잇는다.", BattleTraitCategory.MOMENTUM),
        trait("TRAIT_090", "숨 돌릴 틈 없는 자", "상대에게 반격을 준비할 여유를 거의 주지 않는다.", BattleTraitCategory.MOMENTUM),

        trait("TRAIT_091", "결말을 아는 자", "승부가 끝날 순간을 먼저 알아보고 움직인다.", BattleTraitCategory.FINISH),
        trait("TRAIT_092", "마지막 일격", "남은 힘을 정확한 최후의 공격에 집중한다.", BattleTraitCategory.FINISH),
        trait("TRAIT_093", "승부 종결자", "유리한 전황을 놓치지 않고 확실한 결과로 닫는다.", BattleTraitCategory.FINISH),
        trait("TRAIT_094", "단호한 마무리", "망설임이 패착이 될 순간에 단호한 선택을 내린다.", BattleTraitCategory.FINISH),
        trait("TRAIT_095", "정확한 끝맺음", "과도한 움직임 없이 필요한 한 수로 승부를 끝낸다.", BattleTraitCategory.FINISH),
        trait("TRAIT_096", "최후의 주문", "마지막 마력을 결말에 가장 어울리는 형태로 펼친다.", BattleTraitCategory.FINISH),
        trait("TRAIT_097", "문을 닫는 방패", "상대의 마지막 가능성까지 수비로 차분히 닫아 버린다.", BattleTraitCategory.FINISH),
        trait("TRAIT_098", "결정타의 주인", "쌓아 온 우세를 승패를 가르는 한 번으로 완성한다.", BattleTraitCategory.FINISH),
        trait("TRAIT_099", "끝을 보는 추적자", "도망칠 틈을 주지 않고 승부의 마지막까지 따라간다.", BattleTraitCategory.FINISH),
        trait("TRAIT_100", "기록에 남은 마침표", "전투의 마지막 장면을 오래 기억될 모습으로 남긴다.", BattleTraitCategory.FINISH),
    )

    val byId: Map<String, BattleTraitDefinition> = all.associateBy { it.id }

    fun combatProfileFor(traitIds: List<String>): BattleTraitCombatProfile =
        combatProfileFor(traitIds, emptyMap())

    fun combatProfileFor(
        traitIds: List<String>,
        effectiveRanks: Map<String, Int>,
    ): BattleTraitCombatProfile {
        val profiles = traitIds.distinct().mapNotNull { traitId ->
            byId[traitId]?.combatProfile?.let { profile ->
                val rank = effectiveRanks[traitId]?.coerceIn(1, 5) ?: 1
                val scalePercent = 100 + (rank - 1) * 12
                BattleTraitCombatProfile(
                    aggression = 50 + (profile.aggression - 50) * scalePercent / 100,
                    powerAttack = 50 + (profile.powerAttack - 50) * scalePercent / 100,
                    gamble = 50 + (profile.gamble - 50) * scalePercent / 100,
                    stability = 50 + (profile.stability - 50) * scalePercent / 100,
                    shortFight = 50 + (profile.shortFight - 50) * scalePercent / 100,
                    longFight = 50 + (profile.longFight - 50) * scalePercent / 100,
                )
            }
        }
        if (profiles.isEmpty()) return BattleTraitCombatProfile()
        val totals = IntArray(6)
        profiles.forEach { profile ->
            profile.values().forEachIndexed { index, value -> totals[index] += value }
        }
        return normalizedProfile(totals.map { it / profiles.size })
    }

    fun addTrait(
        state: BattleTraitState,
        traitId: String,
        nowMillis: Long,
    ): BattleTraitMutation {
        val current = normalizeState(state)
        if (traitId !in byId) return BattleTraitMutation(BattleTraitMutationStatus.UNKNOWN_TRAIT, current)
        val existingIndex = current.active.indexOfFirst { it.traitId == traitId }
        if (existingIndex >= 0) {
            val updated = current.active.toMutableList()
            val existing = updated[existingIndex]
            updated[existingIndex] = existing.copy(evidenceCount = safeIncrement(existing.evidenceCount))
            return BattleTraitMutation(
                BattleTraitMutationStatus.EVIDENCE_ADDED,
                current.copy(active = updated),
            )
        }
        if (current.active.size >= BATTLE_MAX_ACTIVE_TRAITS) {
            return BattleTraitMutation(BattleTraitMutationStatus.ACTIVE_LIMIT_REACHED, current)
        }
        return BattleTraitMutation(
            BattleTraitMutationStatus.ADDED,
            current.copy(
                active = current.active + ActiveBattleTrait(
                    traitId = traitId,
                    acquiredAtMillis = nowMillis.coerceAtLeast(0L),
                ),
            ),
        )
    }

    fun requestRemoval(
        state: BattleTraitState,
        traitId: String,
        nowMillis: Long,
    ): BattleTraitMutation {
        val current = normalizeState(state)
        if (current.removal != null) {
            return BattleTraitMutation(BattleTraitMutationStatus.REMOVAL_ALREADY_PENDING, current)
        }
        if (current.active.none { it.traitId == traitId }) {
            return BattleTraitMutation(BattleTraitMutationStatus.TRAIT_NOT_ACTIVE, current)
        }
        val requestedAt = nowMillis.coerceAtLeast(0L)
        return BattleTraitMutation(
            BattleTraitMutationStatus.REMOVAL_SCHEDULED,
            current.copy(
                removal = BattleTraitRemoval(
                    traitId = traitId,
                    requestedAtMillis = requestedAt,
                    readyAtMillis = safeAdd(requestedAt, BATTLE_TRAIT_REMOVAL_MILLIS),
                ),
            ),
        )
    }

    fun cancelRemoval(
        state: BattleTraitState,
        nowMillis: Long,
    ): BattleTraitMutation {
        val current = normalizeState(state)
        val removal = current.removal
            ?: return BattleTraitMutation(BattleTraitMutationStatus.NO_REMOVAL_PENDING, current)
        if (nowMillis >= removal.readyAtMillis) {
            return BattleTraitMutation(BattleTraitMutationStatus.CANCELLATION_WINDOW_CLOSED, current)
        }
        return BattleTraitMutation(
            BattleTraitMutationStatus.REMOVAL_CANCELLED,
            current.copy(removal = null),
        )
    }

    fun completeRemoval(
        state: BattleTraitState,
        nowMillis: Long,
    ): BattleTraitMutation {
        val current = normalizeState(state)
        val removal = current.removal
            ?: return BattleTraitMutation(BattleTraitMutationStatus.NO_REMOVAL_PENDING, current)
        if (nowMillis < removal.readyAtMillis) {
            return BattleTraitMutation(BattleTraitMutationStatus.NOT_READY, current)
        }
        return BattleTraitMutation(
            BattleTraitMutationStatus.REMOVED,
            current.copy(
                active = current.active.filterNot { it.traitId == removal.traitId },
                removal = null,
            ),
        )
    }

    /** Selects one narrative label from structural battle evidence; it never changes the battle. */
    fun suggestedTrait(
        result: ProjectionBattleResult,
        side: BattleSide = BattleSide.USER,
    ): BattleTraitDefinition {
        val actions = result.rounds.map {
            if (side == BattleSide.USER) it.userAction else it.opponentAction
        }
        val hpAfter = result.rounds.map {
            if (side == BattleSide.USER) it.userHpAfter else it.opponentHpAfter
        }
        val sideWon = when (side) {
            BattleSide.USER -> result.outcome == BattleOutcome.USER_WIN
            BattleSide.OPPONENT -> result.outcome == BattleOutcome.USER_LOSS
        }
        val guards = actions.count { it.kind == BattleActionKind.GUARD }
        val skills = actions.count { it.kind == BattleActionKind.SKILL }
        val attacks = actions.count { it.kind == BattleActionKind.BASIC_ATTACK }
        val criticals = actions.count { it.critical }
        val category = when {
            sideWon && hpAfter.dropLast(1).any { it in 1..250 } -> BattleTraitCategory.REVERSAL
            sideWon && result.rounds.size <= 3 -> BattleTraitCategory.FINISH
            guards >= maxOf(2, actions.size / 2) -> BattleTraitCategory.DEFENSE
            result.rounds.size >= 8 -> BattleTraitCategory.ENDURANCE
            criticals >= 2 -> BattleTraitCategory.PRECISION
            skills >= 3 -> BattleTraitCategory.TACTICS
            attacks >= 5 -> BattleTraitCategory.OFFENSE
            consecutivePressure(actions) >= 3 -> BattleTraitCategory.MOMENTUM
            actions.firstOrNull()?.kind == BattleActionKind.BASIC_ATTACK -> BattleTraitCategory.OPENING
            else -> BattleTraitCategory.TEMPERAMENT
        }
        val candidates = all.filter { it.category == category }
        val key = "${result.battleId}:${result.serverSeed}:${side.name}:${actions.joinToString { it.kind.name }}"
        val index = ((stableHash(key) ushr 1) % candidates.size.toLong()).toInt()
        return candidates[index]
    }

    private fun normalizeState(state: BattleTraitState): BattleTraitState {
        val active = state.active
            .asSequence()
            .filter { it.traitId in byId }
            .distinctBy { it.traitId }
            .take(BATTLE_MAX_ACTIVE_TRAITS)
            .map {
                it.copy(
                    acquiredAtMillis = it.acquiredAtMillis.coerceAtLeast(0L),
                    evidenceCount = it.evidenceCount.coerceAtLeast(1),
                )
            }
            .toList()
        val removal = state.removal?.takeIf { pending -> active.any { it.traitId == pending.traitId } }
        return BattleTraitState(active = active, removal = removal)
    }

    private fun consecutivePressure(actions: List<com.nullplaying.model.BattleRoundAction>): Int {
        var longest = 0
        var current = 0
        actions.forEach { action ->
            if (action.kind != BattleActionKind.GUARD) {
                current += 1
                longest = maxOf(longest, current)
            } else {
                current = 0
            }
        }
        return longest
    }

    private fun trait(
        id: String,
        nameKo: String,
        descriptionKo: String,
        category: BattleTraitCategory,
    ): BattleTraitDefinition = BattleTraitDefinition(
        id = id,
        nameKo = nameKo,
        descriptionKo = descriptionKo,
        category = category,
        combatProfile = profileForCategory(id, category),
    )

    private fun profileForCategory(
        id: String,
        category: BattleTraitCategory,
    ): BattleTraitCombatProfile {
        val base = when (category) {
            BattleTraitCategory.OPENING -> listOf(65, 45, 45, 45, 75, 25)
            BattleTraitCategory.OFFENSE -> listOf(75, 70, 50, 30, 55, 20)
            BattleTraitCategory.DEFENSE -> listOf(25, 35, 35, 85, 35, 85)
            BattleTraitCategory.REVERSAL -> listOf(40, 55, 90, 40, 25, 50)
            BattleTraitCategory.ENDURANCE -> listOf(30, 35, 45, 75, 20, 95)
            BattleTraitCategory.PRECISION -> listOf(40, 55, 60, 80, 30, 35)
            BattleTraitCategory.TACTICS -> listOf(40, 45, 65, 70, 35, 45)
            BattleTraitCategory.TEMPERAMENT -> listOf(50, 50, 50, 50, 50, 50)
            BattleTraitCategory.MOMENTUM -> listOf(70, 55, 55, 35, 60, 25)
            BattleTraitCategory.FINISH -> listOf(50, 75, 85, 45, 30, 15)
        }.toMutableList()
        fun shift(lane: Int): Int = Math.floorMod(stableHash("$id:$lane"), 11L).toInt() - 5
        val aggressionShift = shift(0)
        val powerShift = shift(1)
        val gambleShift = shift(2)
        base[0] += aggressionShift
        base[3] -= aggressionShift
        base[1] += powerShift
        base[5] -= powerShift
        base[2] += gambleShift
        base[4] -= gambleShift
        return normalizedProfile(base)
    }

    private fun normalizedProfile(values: List<Int>): BattleTraitCombatProfile {
        val safe = values.map { it.coerceIn(0, 100) }
        val total = safe.sum().coerceAtLeast(1)
        val scaled = safe.map { it * 300 / total }.toMutableList()
        var remainder = 300 - scaled.sum()
        val priority = safe.indices.sortedByDescending { index -> (safe[index] * 300) % total }
        var cursor = 0
        while (remainder > 0) {
            val index = priority[cursor % priority.size]
            if (scaled[index] < 100) {
                scaled[index] += 1
                remainder -= 1
            }
            cursor += 1
        }
        return BattleTraitCombatProfile(
            aggression = scaled[0],
            powerAttack = scaled[1],
            gamble = scaled[2],
            stability = scaled[3],
            shortFight = scaled[4],
            longFight = scaled[5],
        )
    }

    private fun safeIncrement(value: Int): Int = if (value == Int.MAX_VALUE) value else value + 1

    private fun safeAdd(left: Long, right: Long): Long =
        if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private fun stableHash(value: String): Long {
        var hash = -3_750_763_034_362_895_579L
        for (character in value) {
            hash = hash xor character.code.toLong()
            hash *= 1_099_511_628_211L
        }
        return hash
    }
}
