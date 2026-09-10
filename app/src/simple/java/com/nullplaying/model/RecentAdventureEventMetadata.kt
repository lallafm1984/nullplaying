package com.nullplaying.model

/**
 * Versioned metadata stored inside [RecentAdventureEvent.contextName].
 *
 * The recent-event Room schema intentionally stays unchanged so rows written by older releases
 * remain readable. Unknown or malformed tokens are ignored by readers.
 */
internal object RecentAdventureEventMetadata {
    private const val STAT_GROWTH_VERSION = "STAT_GROWTH_V1"
    private const val REWARD_PREFIX = "REWARD="
    private const val ROUTE_USES_PREFIX = "ROUTE_USES="
    private const val ROUTE_DELAY_PREFIX = "ROUTE_DELAY="
    private const val ITEM_NAME_PREFIX = "ITEM_NAME="
    private const val ITEM_EQUIPPED_PREFIX = "ITEM_EQUIPPED="
    private const val XP_PREFIX = "XP="
    private const val GOLD_PREFIX = "GOLD="

    private val statKeys = listOf(
        "STR",
        "CON",
        "DEX",
        "INT",
        "WIS",
        "CHA",
        "HP_MAX",
        "MP_MAX",
    )

    fun encodeStatGrowth(before: HeroStats, after: HeroStats): String = buildList {
        add(STAT_GROWTH_VERSION)
        statKeys.zip(before.values().zip(after.values())).forEach { (key, values) ->
            val (oldValue, newValue) = values
            if (newValue > oldValue) add("$key=${newValue - oldValue}")
        }
    }.joinToString("|")

    fun decodeStatGrowth(encoded: String): List<Pair<String, Long>> {
        val parts = encoded.split('|')
        if (parts.firstOrNull() != STAT_GROWTH_VERSION) return emptyList()
        val allowedKeys = statKeys.toSet()
        return parts.drop(1).mapNotNull { token ->
            val key = token.substringBefore('=', missingDelimiterValue = "")
            val value = token.substringAfter('=', missingDelimiterValue = "").toLongOrNull()
            if (key !in allowedKeys || value == null || value <= 0L) null else key to value
        }.distinctBy { it.first }
    }

    fun appendAdventureReward(
        baseContext: String,
        rewardKind: AdventureEventRewardKind,
        routeRewardUses: Int,
        routeDelayMillis: Long,
        itemReward: AdventureEventItemReward? = null,
    ): String = buildList {
        add(baseContext)
        add("$REWARD_PREFIX${rewardKind.name}")
        if (rewardKind == AdventureEventRewardKind.ITEM && itemReward != null) add("ITEM_KIND=${itemReward.name}")
        if (rewardKind == AdventureEventRewardKind.ROUTE) {
            add("$ROUTE_USES_PREFIX${routeRewardUses.coerceIn(2, 10)}")
            add("$ROUTE_DELAY_PREFIX$routeDelayMillis")
        }
    }.joinToString(":")

    fun adventureItemReward(context: String): AdventureEventItemReward? = context.split(':')
        .firstOrNull { it.startsWith("ITEM_KIND=") }?.removePrefix("ITEM_KIND=")
        ?.let { runCatching { AdventureEventItemReward.valueOf(it) }.getOrNull() }

    fun adventureRewardKind(context: String): AdventureEventRewardKind? = context
        .split(':')
        .firstOrNull { it.startsWith(REWARD_PREFIX) }
        ?.removePrefix(REWARD_PREFIX)
        ?.let { encoded -> runCatching { AdventureEventRewardKind.valueOf(encoded) }.getOrNull() }

    fun appendRelationshipReward(
        baseContext: String,
        rewardKind: AdventureEventRewardKind,
        experienceAwarded: Long,
        goldAwarded: Long,
        itemName: String,
        itemEquipped: Boolean,
    ): String = buildList {
        add(baseContext)
        add("$REWARD_PREFIX${rewardKind.name}")
        when (rewardKind) {
            AdventureEventRewardKind.EXPERIENCE -> add("$XP_PREFIX${experienceAwarded.coerceAtLeast(0L)}")
            AdventureEventRewardKind.GOLD -> add("$GOLD_PREFIX${goldAwarded.coerceAtLeast(0L)}")
            AdventureEventRewardKind.ITEM -> {
                if (itemName.isNotBlank() && ':' !in itemName && '|' !in itemName) {
                    add("$ITEM_NAME_PREFIX$itemName")
                }
                add("$ITEM_EQUIPPED_PREFIX$itemEquipped")
            }
            AdventureEventRewardKind.ROUTE, AdventureEventRewardKind.UNSPECIFIED -> Unit
        }
    }.joinToString(":")

    fun relationshipExperience(context: String): Long = longToken(context, XP_PREFIX)

    fun relationshipGold(context: String): Long = longToken(context, GOLD_PREFIX)

    fun relationshipItemName(context: String): String = context
        .split(':')
        .firstOrNull { it.startsWith(ITEM_NAME_PREFIX) }
        ?.removePrefix(ITEM_NAME_PREFIX)
        .orEmpty()

    fun relationshipItemEquipped(context: String): Boolean = context
        .split(':')
        .firstOrNull { it.startsWith(ITEM_EQUIPPED_PREFIX) }
        ?.removePrefix(ITEM_EQUIPPED_PREFIX)
        ?.toBooleanStrictOrNull()
        ?: false

    private fun longToken(context: String, prefix: String): Long = context
        .split(':')
        .firstOrNull { it.startsWith(prefix) }
        ?.removePrefix(prefix)
        ?.toLongOrNull()
        ?.coerceAtLeast(0L)
        ?: 0L

    fun isRouteShortening(context: String): Boolean = context
        .split(':')
        .firstOrNull { it.startsWith(ROUTE_DELAY_PREFIX) }
        ?.removePrefix(ROUTE_DELAY_PREFIX)
        ?.toLongOrNull()
        ?.let { it < 0L }
        ?: false
}
