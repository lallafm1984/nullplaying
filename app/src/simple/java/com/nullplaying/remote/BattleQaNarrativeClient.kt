package com.nullplaying.remote

import com.nullplaying.BuildConfig
import com.nullplaying.engine.BattleTraitCatalog
import com.nullplaying.model.BattleRoundAction
import com.nullplaying.model.BattleProjectionSnapshot
import com.nullplaying.model.ProjectionBattleResult
import com.nullplaying.model.UserInitiatedBattleRequest
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val BATTLE_QA_SCHEMA_VERSION = 2
private const val BATTLE_QA_BRIDGE_PORT = 8787
private const val BATTLE_QA_RESPONSE_LIMIT_BYTES = 64 * 1024
private const val BATTLE_QA_SESSION_HEADER = "X-AlarmQuest-Battle-QA-Session"
internal const val BATTLE_QA_SESSION_EXTRA = "battle_qa_session"
private val battleQaSessionTokenPattern = Regex("^[a-f0-9]{64}$")
private val battleQaRequestIdPattern = Regex(
    "^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
    RegexOption.IGNORE_CASE,
)

private val battleQaAllowedHosts = setOf("10.0.2.2", "127.0.0.1", "localhost")
private val battleQaEffectKeys = setOf(
    "CLASH",
    "SLASH",
    "HEAVY_HIT",
    "GUARD",
    "EVADE",
    "COUNTER",
    "MAGIC",
    "FINISH",
)
private val quotedBattleDialoguePatterns = listOf(
    Regex("\"[^\"\\r\\n]*\""),
    Regex("“[^”\\r\\n]*”"),
    Regex("「[^」\\r\\n]*」"),
    Regex("『[^』\\r\\n]*』"),
    Regex("‘[^’\\r\\n]*’"),
)
private val battleDialogueQuoteCharacters = Regex("[\"“”「」『』‘’]")
private val battleWhitespace = Regex("\\s+")
private val battlePunctuationSpacing = Regex("\\s+([,.!?…:;])")
private val battleNarrationSentencePattern = Regex("[^.!?…。！？]+(?:[.!?…。！？]+|$)")
private val battleSentenceEndingPattern = Regex("[.!?…。！？]+$")
private val battleOutcomeWordPattern = Regex("승리|패배|무승부")

internal object BattleQaSessionToken {
    @Volatile
    private var value: String? = null

    fun configure(rawValue: String?) {
        if (rawValue == null) return
        value = rawValue.trim().takeIf { battleQaSessionTokenPattern.matches(it) }
    }

    fun current(): String? = value
}

internal fun battleQaRequestIdAllowed(value: String): Boolean =
    battleQaRequestIdPattern.matches(value) &&
        runCatching { UUID.fromString(value) }.isSuccess

@Serializable
internal data class BattleQaUsage(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
)

@Serializable
internal data class BattleQaScene(
    val phaseId: String = "",
    val title: String = "",
    val text: String = "",
    val dialogue: String? = null,
    val effectKey: String = "",
    val templateIds: List<String> = emptyList(),
)

@Serializable
internal data class BattleQaNamedSkillRequest(
    val id: String,
    val name: String,
    val kind: String,
)

@Serializable
internal data class BattleQaEquipmentRequest(
    val id: String,
    val name: String,
    val slot: String,
    val rarity: String,
    val narrativeTag: String,
)

@Serializable
internal data class BattleQaTraitRequest(
    val id: String,
    val name: String,
    val description: String,
    val category: String,
)

@Serializable
internal data class BattleQaCombatantRequest(
    val name: String,
    val heroClass: String,
    val level: Long,
    val combatPower: Long,
    val score: Int,
    val guidance: String,
    val skills: List<BattleQaNamedSkillRequest>,
    val equipment: List<BattleQaEquipmentRequest>,
    val traits: List<BattleQaTraitRequest>,
)

@Serializable
internal data class BattleQaActionRequest(
    val kind: String,
    val skillId: String,
    val damage: Int,
    val healing: Int,
    val critical: Boolean,
)

@Serializable
internal data class BattleQaRoundRequest(
    val number: Int,
    val userHpAfter: Int,
    val opponentHpAfter: Int,
    val userAction: BattleQaActionRequest,
    val opponentAction: BattleQaActionRequest,
)

@Serializable
internal data class BattleQaBattleRequest(
    val battleId: String,
    val outcome: String,
    val user: BattleQaCombatantRequest,
    val opponent: BattleQaCombatantRequest,
    val rounds: List<BattleQaRoundRequest>,
)

@Serializable
internal data class BattleQaNarrativeRequest(
    val phaseCount: Int,
    val requestId: String,
    val battle: BattleQaBattleRequest,
)

@Serializable
internal data class BattleQaNarrative(
    val schemaVersion: Int = 0,
    val requestId: String = "",
    val battleId: String = "",
    val userName: String = "",
    val opponentName: String = "",
    val languageTag: String = "ko",
    val phaseCount: Int = 0,
    val source: String = "",
    val modelValid: Boolean = false,
    val model: String = "",
    val syntheticOnly: Boolean = false,
    val productionDatabaseTouched: Boolean = true,
    val attemptCount: Int = 0,
    val latencyMs: Long = 0L,
    val usage: BattleQaUsage = BattleQaUsage(),
    val estimatedCostUsd: Double = 0.0,
    val scenes: List<BattleQaScene> = emptyList(),
)

/**
 * Dialogue is never part of battle playback. Paired quoted spans are removed, and unmatched
 * quote marks are dropped as a final display-boundary safeguard.
 */
internal fun stripBattleDialogueText(value: String): String {
    var sanitized = value
    quotedBattleDialoguePatterns.forEach { pattern ->
        sanitized = pattern.replace(sanitized, " ")
    }
    return sanitized
        .replace(battleDialogueQuoteCharacters, "")
        .replace(battleWhitespace, " ")
        .replace(battlePunctuationSpacing, "$1")
        .trim()
}

internal fun sanitizeBattleQaNarrative(narrative: BattleQaNarrative): BattleQaNarrative =
    narrative.copy(
        scenes = narrative.scenes.map { scene ->
            scene.copy(
                text = stripBattleDialogueText(scene.text),
                dialogue = null,
            )
        },
    )

private fun battleNarrationSentences(value: String): List<String> =
    battleNarrationSentencePattern.findAll(value)
        .map { it.value.trim() }
        .filter(String::isNotBlank)
        .toList()

private fun battleNamedOutcome(
    text: String,
    name: String,
    outcome: String,
): Boolean = Regex("${Regex.escape(name)}[이가]?\\s*$outcome").containsMatchIn(text)

internal fun battleFinalOutcomeMatches(
    outcome: String,
    userName: String,
    opponentName: String,
    text: String,
): Boolean = when (outcome) {
    "USER_WIN" -> battleNamedOutcome(text, userName, "승리") &&
        battleNamedOutcome(text, opponentName, "패배")
    "USER_LOSS" -> battleNamedOutcome(text, opponentName, "승리") &&
        battleNamedOutcome(text, userName, "패배")
    "DRAW" -> "무승부" in text
    else -> false
}

internal fun battleQaBridgeEndpointAllowed(rawEndpoint: String): Boolean {
    val endpoint = runCatching { URI(rawEndpoint) }.getOrNull() ?: return false
    return endpoint.scheme == "http" &&
        endpoint.host?.lowercase() in battleQaAllowedHosts &&
        endpoint.port == BATTLE_QA_BRIDGE_PORT &&
        endpoint.path == "/v1/narratives" &&
        endpoint.rawUserInfo == null &&
        endpoint.rawQuery == null &&
        endpoint.rawFragment == null
}

internal fun validateBattleQaNarrative(
    narrative: BattleQaNarrative,
    requestedPhaseCount: Int,
    requestedId: String,
    requestedBattleId: String,
    requestedUserName: String,
    requestedOpponentName: String,
    requestedOutcome: String,
): List<String> {
    val errors = mutableListOf<String>()
    if (requestedPhaseCount !in 3..5) errors += "battle narrative anchors must contain 3..5 items"
    if (narrative.schemaVersion != BATTLE_QA_SCHEMA_VERSION) errors += "schema version mismatch"
    if (narrative.requestId != requestedId || !battleQaRequestIdAllowed(narrative.requestId)) {
        errors += "request id mismatch"
    }
    if (narrative.phaseCount != requestedPhaseCount) errors += "phase count mismatch"
    if (narrative.battleId != requestedBattleId) errors += "battle id mismatch"
    if (narrative.userName != requestedUserName) errors += "user name mismatch"
    if (narrative.opponentName != requestedOpponentName) errors += "opponent name mismatch"
    if (narrative.source !in setOf("qwen", "local_template")) errors += "source is invalid"
    if (narrative.model.isBlank() || narrative.model.length > 80) errors += "model is invalid"
    if (!narrative.syntheticOnly) errors += "response is not synthetic"
    if (narrative.productionDatabaseTouched) errors += "production database flag is unsafe"
    if (narrative.attemptCount !in 1..3) errors += "attempt count is invalid"
    if (narrative.latencyMs !in 0L..180_000L) errors += "latency is invalid"
    if (narrative.usage.inputTokens < 0 || narrative.usage.outputTokens < 0) errors += "usage is invalid"
    if (narrative.estimatedCostUsd !in 0.0..0.01) errors += "cost is invalid"
    if (narrative.scenes.size != requestedPhaseCount) errors += "scene count mismatch"

    narrative.scenes.forEachIndexed { index, scene ->
        if (scene.phaseId != "P${index + 1}") errors += "scene $index phase id mismatch"
        if (scene.title.length !in 2..28) errors += "scene $index title is invalid"
        if (scene.text.isBlank() || scene.text.length > 600) errors += "scene $index text is invalid"
        val sentences = battleNarrationSentences(scene.text)
        if (sentences.size !in 3..4 || !battleSentenceEndingPattern.containsMatchIn(scene.text.trim())) {
            errors += "scene $index must contain 3..4 complete sentences"
        }
        val textBeforeFinalLine = if (index == requestedPhaseCount - 1) {
            sentences.dropLast(1).joinToString(" ")
        } else {
            scene.text
        }
        if (battleOutcomeWordPattern.containsMatchIn(textBeforeFinalLine)) {
            errors += "scene $index reveals the result before the final narration line"
        }
        if (
            index == requestedPhaseCount - 1 &&
            !battleFinalOutcomeMatches(
                outcome = requestedOutcome,
                userName = requestedUserName,
                opponentName = requestedOpponentName,
                text = sentences.lastOrNull().orEmpty(),
            )
        ) {
            errors += "final narration line does not match the deterministic outcome"
        }
        if (scene.dialogue != null) errors += "scene $index dialogue must be absent"
        if (battleDialogueQuoteCharacters.containsMatchIn(scene.text)) {
            errors += "scene $index contains quoted dialogue"
        }
        if (scene.effectKey !in battleQaEffectKeys) errors += "scene $index effect is invalid"
        if (Regex("[AB]_(SKILL_[0-9]{2}|ITEM_[A-Z_]+|TRAIT_[0-9]{2})").containsMatchIn(scene.text)) {
            errors += "scene $index contains an unresolved entity reference"
        }
    }
    return errors
}

internal fun battleQaFailureMessage(error: Throwable): String = when (error) {
    is IOException -> "Mac의 로컬 Qwen 브리지를 확인해 주세요. npm run battle:qwen:bridge"
    else -> "AI 장면 응답을 검증하지 못했습니다. 로컬 브리지를 다시 시작해 주세요."
}

internal class BattleQaNarrativeClient(
    private val endpoint: String = BuildConfig.BATTLE_QA_BRIDGE_URL,
    private val enabled: Boolean = BuildConfig.BATTLE_QA_BRIDGE_ENABLED &&
        BuildConfig.DEBUG &&
        !BuildConfig.REMOTE_SERVICES_ENABLED,
    private val sessionToken: () -> String? = BattleQaSessionToken::current,
) {
    private val json = Json { ignoreUnknownKeys = false }

    val isAvailable: Boolean
        get() = enabled &&
            battleQaBridgeEndpointAllowed(endpoint) &&
            battleQaSessionTokenPattern.matches(sessionToken().orEmpty())

    suspend fun generate(
        phaseCount: Int,
        requestId: String,
        battleRequest: UserInitiatedBattleRequest,
        battleResult: ProjectionBattleResult,
        userCombatPower: Long,
        opponentCombatPower: Long,
        userScore: Int,
    ): BattleQaNarrative {
        require(phaseCount in 3..5) { "Battle QA narrative requires 3..5 engine anchors" }
        require(battleQaRequestIdAllowed(requestId)) { "requestId must be a UUID" }
        check(isAvailable) { "Battle QA bridge is unavailable for this build" }
        val activeSessionToken = checkNotNull(sessionToken())
        require(battleResult.battleId == battleRequest.battleId) { "battle result identity mismatch" }
        val requestPayload = BattleQaNarrativeRequest(
            phaseCount = phaseCount,
            requestId = requestId,
            battle = BattleQaBattleRequest(
                battleId = battleRequest.battleId,
                outcome = battleResult.outcome.name,
                user = battleRequest.user.toQaCombatant(
                    combatPower = userCombatPower,
                    score = userScore,
                ),
                opponent = battleRequest.opponent.toQaCombatant(
                    combatPower = opponentCombatPower,
                    score = battleRequest.opponentReferenceScore,
                ),
                rounds = battleResult.rounds.map { round ->
                    BattleQaRoundRequest(
                        number = round.number,
                        userHpAfter = round.userHpAfter,
                        opponentHpAfter = round.opponentHpAfter,
                        userAction = round.userAction.toQaAction(),
                        opponentAction = round.opponentAction.toQaAction(),
                    )
                },
            ),
        )
        return runInterruptible(Dispatchers.IO) {
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5_000
                readTimeout = 70_000
                doOutput = true
                useCaches = false
                instanceFollowRedirects = false
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty(BATTLE_QA_SESSION_HEADER, activeSessionToken)
            }
            try {
                connection.outputStream.use { output ->
                    output.write(json.encodeToString(requestPayload).toByteArray(Charsets.UTF_8))
                }
                val status = connection.responseCode
                if (status != HttpURLConnection.HTTP_OK) {
                    connection.errorStream?.use { it.readUtf8Limited(BATTLE_QA_RESPONSE_LIMIT_BYTES) }
                    throw IOException("Battle QA bridge returned HTTP $status")
                }
                val body = connection.inputStream.use {
                    it.readUtf8Limited(BATTLE_QA_RESPONSE_LIMIT_BYTES)
                }
                val narrative = sanitizeBattleQaNarrative(
                    json.decodeFromString<BattleQaNarrative>(body),
                )
                val errors = validateBattleQaNarrative(
                    narrative = narrative,
                    requestedPhaseCount = phaseCount,
                    requestedId = requestId,
                    requestedBattleId = requestPayload.battle.battleId,
                    requestedUserName = requestPayload.battle.user.name,
                    requestedOpponentName = requestPayload.battle.opponent.name,
                    requestedOutcome = requestPayload.battle.outcome,
                )
                check(errors.isEmpty()) { errors.joinToString(limit = 5) }
                narrative
            } finally {
                connection.disconnect()
            }
        }
    }
}

private fun BattleProjectionSnapshot.toQaCombatant(
    combatPower: Long,
    score: Int,
): BattleQaCombatantRequest = BattleQaCombatantRequest(
    name = displayName,
    heroClass = heroClass.name,
    level = level,
    combatPower = combatPower,
    score = score,
    guidance = guidance.name,
    skills = skills.map { skill ->
        BattleQaNamedSkillRequest(
            id = skill.skillId,
            name = skill.displayName,
            kind = skill.kind.name,
        )
    },
    equipment = equipment.map { item ->
        BattleQaEquipmentRequest(
            id = item.itemId,
            name = item.displayName,
            slot = item.slot.name,
            rarity = item.rarity,
            narrativeTag = item.narrativeTag,
        )
    },
    traits = activeTraitIds.mapNotNull(BattleTraitCatalog.byId::get).take(3).map { trait ->
        BattleQaTraitRequest(
            id = trait.id,
            name = trait.nameKo,
            description = trait.descriptionKo,
            category = trait.category.name,
        )
    },
)

private fun BattleRoundAction.toQaAction(): BattleQaActionRequest = BattleQaActionRequest(
    kind = kind.name,
    skillId = skillId,
    damage = damage,
    healing = healing,
    critical = critical,
)

private fun java.io.InputStream.readUtf8Limited(maxBytes: Int): String {
    val bytes = ByteArrayOutputStream()
    val buffer = ByteArray(4 * 1024)
    var total = 0
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        total += count
        if (total > maxBytes) throw IOException("Battle QA bridge response is too large")
        bytes.write(buffer, 0, count)
    }
    return bytes.toString(Charsets.UTF_8.name())
}
