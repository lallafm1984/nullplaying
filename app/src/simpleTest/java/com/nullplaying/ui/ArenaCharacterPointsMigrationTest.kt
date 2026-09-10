package com.nullplaying.ui

import com.nullplaying.engine.SkillCatalog
import com.nullplaying.engine.arena.*
import com.nullplaying.localization.AppLanguage
import com.nullplaying.model.HeroClass
import org.junit.Assert.*
import org.junit.Test

class ArenaCharacterPointsMigrationTest {
    private val heroClass = HeroClass.RANGER
    private fun owned(level: Long) = SkillCatalog.forClass(heroClass).filter { it.unlockLevel <= level }.mapTo(linkedSetOf()) { it.catalogId }
    private fun source(points: Int) = BattleLocalSnapshot(
        gameEpochDay = 40, entriesRemaining = 3, dailyBattleDay = 40, dailyBattlesUsed = 20,
        arenaTrustedEpochMillis = 3_500_000, arenaTrustedElapsedRealtimeMillis = 76_000,
        arenaTrustedBootCount = 12, arenaClockServerAnchored = true,
        rewardedRefillDay = 40, rewardedRefillsUsed = 1, rewardedRefillRequestIds = listOf("ad-old"),
        score = 676, placementCompleted = 40, wins = 14, losses = 26,
        arenaProgression = ArenaProgressionState(unlocked = true, totalXp = ArenaProgressionRules.xpForLevel(points), revision = 77),
        arenaSkillTree = ArenaSkillTreeRules.autoAllocate(heroClass, points, owned(20), 41).copy(revision = 28),
    )
    private fun migrate(source: BattleLocalSnapshot, level: Long): BattleLocalSnapshot {
        val recovered = recoverArenaProgression(source)
        return reconcileArenaSkillTreeForFighter(
            recovered.copy(arenaProgression = ArenaCharacterPointRules.migrate(recovered.arenaProgression, level)),
            heroClass, owned(level), heroLevel = level,
        )
    }

    @Test fun `valid allocation survives with newly available points and no economic or clock changes`() {
        val old = source(5)
        val migrated = migrate(old, 20)
        assertTrue(migrated.arenaProgression.characterLevelPoints)
        assertEquals(old.arenaSkillTree, migrated.arenaSkillTree)
        assertEquals(old, migrated.copy(arenaProgression = old.arenaProgression))
        val view = ArenaSkillTreeRules.view(migrated.arenaSkillTree!!, 20, owned(20))
        assertEquals(5, view.spentPoints)
        assertEquals(15, view.availablePoints)
        assertEquals(migrated, migrate(migrated, 20))
        assertEquals(migrated, decodeBattleLocalSnapshot(encodeBattleLocalSnapshot(migrated)))
        assertEquals(20, ArenaSkillTreeRules.view(migrate(migrated, 25).arenaSkillTree!!, 25, owned(25)).availablePoints)
    }

    @Test fun `overbudget legacy allocation gets full free reset without keeping excess points`() {
        val old = source(30)
        val migrated = migrate(old, 20)
        assertEquals(emptyList<ArenaSkillAllocation>(), migrated.arenaSkillTree!!.allocations)
        assertEquals(29L, migrated.arenaSkillTree!!.revision)
        assertEquals(old, migrated.copy(arenaProgression = old.arenaProgression, arenaSkillTree = old.arenaSkillTree))
        assertEquals(20, ArenaSkillTreeRules.view(migrated.arenaSkillTree!!, 20, owned(20)).availablePoints)
        assertEquals(migrated, migrate(migrated, 20))
    }

    @Test fun `pending legacy result recovers once before migrating without replaying score or ticket settlement`() {
        val old = source(5)
        val id = "captured-legacy"
        val history = BattlePreviewHistory(
            battleId = id, userName = "Hero", opponentName = "Foe", opponentClass = "메이지",
            opponentLevel = 20L, resultLabel = "승리", pointDelta = 12, summary = "Saved result",
            narrativeLines = listOf("Captured battle"), skillNames = emptyList(), equipment = emptyList(),
            traitNames = emptyList(), completedAtMillis = 3_000_000L, narrativeSource = "arena_local",
        )
        val reserved = old.copy(history = listOf(history), arenaProgression = ArenaProgressionRules.reserveBattle(
            old.arenaProgression, id, 40, outcome = com.nullplaying.model.BattleOutcome.USER_WIN))
        val recovered = migrate(reserved, 20)
        assertNull(recovered.arenaProgression.pending)
        assertEquals(old.arenaProgression.totalXp + 120L, recovered.arenaProgression.totalXp)
        assertTrue(recovered.arenaProgression.characterLevelPoints)
        assertEquals(reserved, recovered.copy(arenaProgression = reserved.arenaProgression))
        val relaunched = decodeBattleLocalSnapshot(encodeBattleLocalSnapshot(recovered))!!
        assertEquals(recovered, migrate(relaunched, 20))
    }

    @Test fun `skill configuration shows the hero-level point budget in every language`() {
        val tree = source(5).arenaSkillTree!!
        for (language in AppLanguage.entries) {
            val model = arenaSkillTreeUiModel(ArenaSkillTreeRules.view(tree, 20, owned(20)), true, true, language)
            assertTrue(arenaSkillTreeEntryDetail(model, language).contains("15"))
            assertEquals(20, model.spentPoints + model.availablePoints)
            assertFalse(arenaSkillTreeEntryDetail(model, language).contains("XP"))
            assertTrue(arenaGuideContent(language).lines.any { it.contains("100") })
        }
    }
}
