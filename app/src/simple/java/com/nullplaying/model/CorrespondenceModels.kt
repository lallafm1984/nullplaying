package com.nullplaying.model

import kotlinx.serialization.Serializable

// Archived save schema only. The correspondence runtime and UI have been removed.

@Serializable
enum class CorrespondenceTopic {
    WORLD_OPENED,
    SKILL_LEARNED,
    SKILL_MASTERY,
    TALE_COMPLETED,
    TITLE_UNLOCKED,
    RARE_EQUIPMENT,
    RANKING_PROJECTION,
}

@Serializable
enum class CorrespondenceStatus {
    AVAILABLE,
    /** Legacy V1 value retained for saved-character compatibility. */
    REPLIED,
    PENDING_DECISION,
    OUTCOME_AVAILABLE,
    RESOLVED,
    SELF_RESOLVED,
}

@Serializable
enum class CorrespondenceDecisionOutcome {
    FOLLOWED_ADVICE,
    CHOSE_DIFFERENTLY,
}

@Serializable
enum class CorrespondenceDecisionReason {
    ADVICE,
    DISPOSITION,
    RELATIONSHIP,
    OWN_JUDGMENT,
}

@Serializable
enum class CorrespondenceReplyIntent {
    ENCOURAGE,
    CAUTION,
    TRUST,
    PERSIST,
    AIM_HIGH,
    REST,
    EXPERIMENT,
    PRACTICAL,
    CHERISH,
    APPROACH,
    DISTANCE,
}

@Serializable
data class DispositionAxis(
    var score: Int = 0,
    var evidence: Int = 0,
)

@Serializable
data class HeroDisposition(
    val caution: DispositionAxis = DispositionAxis(),
    val discipline: DispositionAxis = DispositionAxis(),
    val aggression: DispositionAxis = DispositionAxis(),
    val curiosity: DispositionAxis = DispositionAxis(),
    val autonomy: DispositionAxis = DispositionAxis(),
    val sociability: DispositionAxis = DispositionAxis(),
    val persistence: DispositionAxis = DispositionAxis(),
)

@Serializable
data class RankingProjectionSnapshot(
    val characterId: String,
    val displayName: String,
    val heroClass: HeroClass,
    val level: Long,
    val combatPower: Long,
    val rank: Int,
    val observedAt: Long,
)

@Serializable
enum class ProjectionRelationshipStage {
    AWARE,
    WATCHING,
    RIVAL,
    RESPECTED_RIVAL,
    DISTANT,
}

@Serializable
data class ProjectionRelationship(
    val characterId: String,
    var latestSnapshot: RankingProjectionSnapshot,
    var stage: ProjectionRelationshipStage = ProjectionRelationshipStage.AWARE,
    var affinity: Int = 0,
    var rivalry: Int = 0,
    var encounters: Int = 1,
    var updatedAt: Long = latestSnapshot.observedAt,
)

@Serializable
data class CorrespondenceRecord(
    val id: String,
    val sourceEventKey: String,
    val occurredAt: Long,
    val topic: CorrespondenceTopic,
    val subjectId: String = "",
    val subjectName: String = "",
    val contextName: String = "",
    val previousName: String = "",
    val currentName: String = "",
    val previousValue: Long? = null,
    val currentValue: Long? = null,
    val rarity: String = "",
    val projection: RankingProjectionSnapshot? = null,
    var status: CorrespondenceStatus = CorrespondenceStatus.AVAILABLE,
    var replyIntent: CorrespondenceReplyIntent? = null,
    var seenAt: Long = 0L,
    var repliedAt: Long = 0L,
    var decisionDueActionSequence: Long = 0L,
    var decisionRulesVersion: Int = 0,
    var decisionSeed: Long = 0L,
    var resolvedIntent: CorrespondenceReplyIntent? = null,
    var decisionOutcome: CorrespondenceDecisionOutcome? = null,
    var decisionReason: CorrespondenceDecisionReason? = null,
    var resolutionEventKey: String = "",
    var resolvedAt: Long = 0L,
    var outcomeAcknowledgedAt: Long = 0L,
)

@Serializable
data class CorrespondenceState(
    var baselineEstablished: Boolean = false,
    val records: MutableList<CorrespondenceRecord> = mutableListOf(),
    val disposition: HeroDisposition = HeroDisposition(),
    val relationships: MutableList<ProjectionRelationship> = mutableListOf(),
    var lastRankingMyRank: Int = 0,
    var lastRankingBand: Int = Int.MAX_VALUE,
    var lastRankingProjectionId: String = "",
    var lastRankingActionSequence: Long = 0L,
    var lastVerifiedRankingAt: Long = 0L,
)
