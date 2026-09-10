package com.nullplaying.remote

import com.nullplaying.data.CharacterSlotSnapshot
import com.nullplaying.data.GameSnapshot
import com.nullplaying.data.StartupPhase
import com.nullplaying.engine.SimpleGameEngine
import com.nullplaying.model.HeroClass
import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArenaLiveServerQaContractTest {
    @Test
    fun `live runtime requires exact side by side package project origin and acknowledgement`() {
        val common = ArenaServerQaRuntimeConfig(
            applicationId = ARENA_LIVE_SERVER_QA_APPLICATION_ID,
            debugBuild = true,
            remoteServicesEnabled = false,
            transportEnabled = true,
            adventureSystemEnabled = true,
            sharedPlayerSyncEnabled = true,
            arenaServerMatchingEnabled = true,
            standardSupabaseUrl = "",
            standardSupabasePublishableKey = "",
            qaSupabaseUrl = "https://$ARENA_LIVE_SERVER_PROJECT_REF.supabase.co",
            qaSupabasePublishableKey = "sb_publishable_live_qa",
            liveServerAcknowledgement = ARENA_LIVE_SERVER_PROJECT_REF,
        )
        assertTrue(common.enabled)
        assertFalse(common.copy(applicationId = ARENA_SERVER_QA_APPLICATION_ID).enabled)
        assertFalse(common.copy(liveServerAcknowledgement = "").enabled)
        assertFalse(common.copy(qaSupabaseUrl = "https://abcdefghijklmnopqrst.supabase.co").enabled)
        assertFalse(common.copy(qaSupabaseUrl = common.qaSupabaseUrl + "/rest/v1").enabled)
        assertFalse(common.copy(qaSupabaseUrl = common.qaSupabaseUrl + "?redirect=x").enabled)
        assertFalse(common.copy(standardSupabaseUrl = common.qaSupabaseUrl).enabled)
    }

    @Test
    fun `synthetic requester exactly matches the reviewed live harness payload`() {
        val engine = SimpleGameEngine(
            enableAdventureEvents = true,
            enableAdventureRelationships = true,
            enableAdventureTraits = true,
        )
        val state = createArenaLiveServerQaHero(engine, RUN_TAG, 1_000L)
        val snapshot = GameSnapshot(
            revision = 1L,
            state = state,
            characters = listOf(CharacterSlotSnapshot(1, state)),
            activeSlotId = 1,
            unlockedCharacterSlotCount = 3,
            ready = true,
            startupPhase = StartupPhase.READY,
        )
        val uploads = buildPublicPlayerSnapshotUploads(snapshot, engine::displayCombatPower)

        assertNotNull(uploads)
        assertEquals(1, uploads!!.size)
        assertTrue(isExactArenaLiveServerQaHero(state))
        assertTrue(isExactArenaLiveServerQaUpload(uploads.single(), RUN_TAG))
        assertEquals(ARENA_LIVE_QA_COMBAT_POWER, engine.displayCombatPower(state))
        assertEquals(HeroClass.WARRIOR, uploads.single().heroClass)
        assertTrue(uploads.single().adventureTraitIds.isEmpty())
    }

    @Test
    fun `handoff accepts only the exact requester contract`() {
        val encoded = """{
          "version":1,
          "project_ref":"$ARENA_LIVE_SERVER_PROJECT_REF",
          "run_tag":"$RUN_TAG",
          "requester":{
            "email":"aq-live-${RUN_TAG.lowercase()}-a@example.invalid",
            "password":"temporary-password-123",
            "character_id":"$ARENA_LIVE_QA_HERO_ID",
            "slot_id":1,
            "display_name":"AQ-A-$RUN_TAG",
            "hero_class":"WARRIOR",
            "level":$ARENA_LIVE_QA_HERO_LEVEL,
            "combat_power":$ARENA_LIVE_QA_COMBAT_POWER,
            "rules_version":1,
            "snapshot_version":1,
            "stats":{
              "strength":3350,"constitution":3350,"dexterity":3350,
              "intelligence":3350,"wisdom":3350,"charisma":3350,
              "max_health":200000,"max_mana":100000
            },
            "adventure_trait_ids":[]
          }
        }"""
        val parsed = Json.decodeFromString<ArenaLiveServerQaHandoff>(encoded)

        assertEquals(RUN_TAG, parsed.validated().runTag)
        val altered = encoded.replace(ARENA_LIVE_QA_HERO_ID, "b2200000-0000-4000-8000-000000000002")
        assertTrue(runCatching {
            Json.decodeFromString<ArenaLiveServerQaHandoff>(altered).validated()
        }.isFailure)

        val rejectedHandoffs = mapOf(
            "missing slot" to encoded.replace("            \"slot_id\":1,\n", ""),
            "zero slot" to encoded.replace("\"slot_id\":1", "\"slot_id\":0"),
            "slot two" to encoded.replace("\"slot_id\":1", "\"slot_id\":2"),
            "slot four" to encoded.replace("\"slot_id\":1", "\"slot_id\":4"),
            "string slot" to encoded.replace("\"slot_id\":1", "\"slot_id\":\"1\""),
            "boolean slot" to encoded.replace("\"slot_id\":1", "\"slot_id\":true"),
            "unexpected user id" to encoded.replace(
                "\"slot_id\":1,",
                "\"slot_id\":1,\"user_id\":\"not-part-of-the-handoff\",",
            ),
        )
        rejectedHandoffs.forEach { (case, handoff) ->
            assertTrue(
                "Expected strict rejection for $case",
                runCatching {
                    Json.decodeFromString<ArenaLiveServerQaHandoff>(handoff).validated()
                }.isFailure,
            )
        }
    }

    @Test
    fun `live Activity uses one shot private handoff and an in memory repository`() {
        val activity = projectFile(
            "app/src/arenaLiveServerQa/java/com/nullplaying/ArenaLiveServerQaActivity.kt",
        ).readText()
        assertTrue(activity.contains("arena_live_qa_handoff.json"))
        assertTrue(activity.contains("OsConstants.O_NOFOLLOW"))
        assertTrue(activity.contains("OWNER_READ_WRITE"))
        assertTrue(activity.contains("handoffFile.delete()"))
        assertTrue(activity.contains("finally {\n            bytes.fill(0)"))
        assertTrue(activity.contains("Room.inMemoryDatabaseBuilder"))
        assertTrue(activity.contains("MemoryOnlySimpleStateBackupStore"))
        assertFalse(activity.contains("AlarmQuestApplication"))
        assertTrue(
            activity.indexOf("buildPublicPlayerSnapshotUploads") <
                activity.indexOf("ArenaServerQaSharedPlayerTransport.create"),
        )
    }

    @Test
    fun `live build is separate and keeps the broad remote stack off`() {
        val buildScript = projectFile("app/build.gradle.kts").readText()
        val variant = buildScript.substringAfter("create(\"arenaLiveServerQa\")")
            .substringBefore("getByName(\"release\")")
        assertTrue(variant.contains("applicationIdSuffix = \".arenaliveserverqa\""))
        assertTrue(variant.contains("\"REMOTE_SERVICES_ENABLED\", \"false\""))
        assertTrue(variant.contains("SUPABASE_LIVE_QA_URL".replace("_LIVE_QA", "_QA")))
        assertTrue(variant.contains("arenaLiveQaSupabaseUrl"))
        assertTrue(variant.contains("arenaLiveServerQaAck"))
        assertTrue(variant.contains("\"SUPABASE_URL\", \"\\\"\\\"\""))
        assertTrue(variant.contains("\"ADMOB_APP_ID\", \"\\\"\\\"\""))
        assertFalse(buildScript.contains("ARENA_LIVE_QA_PASSWORD"))
    }

    private fun projectFile(relativePath: String): File {
        val workingDirectory = requireNotNull(System.getProperty("user.dir"))
        val roots = generateSequence(File(workingDirectory).absoluteFile) { it.parentFile }
        val root = roots.firstOrNull { File(it, "app/build.gradle.kts").isFile }
            ?: error("Could not locate project root")
        return File(root, relativePath)
    }

    private companion object {
        const val RUN_TAG = "260907ABCD"
    }
}
