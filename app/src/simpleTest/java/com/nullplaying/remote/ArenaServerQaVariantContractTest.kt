package com.nullplaying.remote

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArenaServerQaVariantContractTest {
    @Test
    fun `variant uses a side by side package and QA-only credential fields`() {
        val buildScript = projectFile("app/build.gradle.kts").readText()
        val variant = buildScript.substringAfter("create(\"arenaServerQa\")")
            .substringBefore("getByName(\"release\")")

        assertTrue(variant.contains("applicationIdSuffix = \".arenaserverqa\""))
        assertTrue(variant.contains("\"REMOTE_SERVICES_ENABLED\", \"false\""))
        assertTrue(variant.contains("\"SHARED_PLAYER_QA_TRANSPORT_ENABLED\", \"true\""))
        assertTrue(variant.contains("\"SHARED_PLAYER_SYNC_ENABLED\", \"true\""))
        assertTrue(variant.contains("\"ADVENTURE_SYSTEM_ENABLED\", \"true\""))
        assertTrue(variant.contains("\"ARENA_SERVER_MATCHING_ENABLED\", \"true\""))
        assertTrue(variant.contains("\"SUPABASE_URL\", \"\\\"\\\"\""))
        assertTrue(variant.contains("\"SUPABASE_PUBLISHABLE_KEY\", \"\\\"\\\"\""))
        assertTrue(variant.contains("escapeBuildConfigString(arenaQaSupabaseUrl)"))
        assertTrue(variant.contains("escapeBuildConfigString(arenaQaSupabasePublishableKey)"))
        assertFalse(variant.contains("buildConfigString(\"SUPABASE_URL\")"))
        assertFalse(variant.contains("buildConfigString(\"SUPABASE_PUBLISHABLE_KEY\")"))
    }

    @Test
    fun `credential validator rejects production URL key and project ref reuse`() {
        val buildScript = projectFile("app/build.gradle.kts").readText()

        assertTrue(buildScript.contains("validateArenaServerQaCredentials"))
        assertTrue(buildScript.contains("!arenaQaSupabaseUrl.equals(productionSupabaseUrl"))
        assertTrue(buildScript.contains("arenaQaSupabasePublishableKey != productionSupabasePublishableKey"))
        assertTrue(buildScript.contains("!qaProjectRef.equals(productionProjectRef"))
        assertTrue(buildScript.contains("dependsOn(validateArenaServerQaCredentials)"))
        assertTrue(buildScript.contains("Arena Server QA artifact contains a production Supabase credential"))
        assertTrue(buildScript.contains("ZipFile(apk)"))
    }

    @Test
    fun `variant keeps internet while removing broad SDK startup components`() {
        val manifest = projectFile("app/src/arenaServerQa/AndroidManifest.xml").readText()

        assertFalse(manifest.contains("android:name=\"android.permission.INTERNET\""))
        assertFalse(manifest.contains("android:name=\"android.permission.ACCESS_NETWORK_STATE\""))
        assertTrue(manifest.contains("com.google.firebase.provider.FirebaseInitProvider"))
        assertTrue(manifest.contains("tools:node=\"remove\""))
        assertTrue(manifest.contains("com.google.android.gms.ads.APPLICATION_ID"))
    }

    @Test
    fun `broad Supabase service cannot consume QA credentials`() {
        val service = projectFile(
            "app/src/simple/java/com/nullplaying/remote/SupabaseGameService.kt",
        ).readText()

        assertTrue(service.contains("BuildConfig.REMOTE_SERVICES_ENABLED"))
        assertTrue(service.contains("BuildConfig.SUPABASE_URL"))
        assertTrue(service.contains("BuildConfig.SUPABASE_PUBLISHABLE_KEY"))
        assertFalse(service.contains("BuildConfig.SUPABASE_QA_URL"))
        assertFalse(service.contains("BuildConfig.SUPABASE_QA_PUBLISHABLE_KEY"))
    }

    @Test
    fun `tracked QA configuration contains no concrete Supabase destination or credential`() {
        val paths = listOf(
            "app/build.gradle.kts",
            "app/src/simple/java/com/nullplaying/remote/ArenaServerQaPolicy.kt",
            "app/src/arenaServerQa/AndroidManifest.xml",
            "app/src/arenaServerQa/java/com/nullplaying/ArenaServerQaActivity.kt",
        )
        val trackedQaText = paths.joinToString("\n") { projectFile(it).readText() }

        assertFalse(Regex("https://[a-z0-9-]+\\.supabase\\.co", RegexOption.IGNORE_CASE)
            .containsMatchIn(trackedQaText))
        assertFalse(Regex("eyJ[a-zA-Z0-9_-]{20,}").containsMatchIn(trackedQaText))
    }

    private fun projectFile(relativePath: String): File {
        val workingDirectory = requireNotNull(System.getProperty("user.dir"))
        val roots = generateSequence(File(workingDirectory).absoluteFile) { it.parentFile }
        val root = roots.firstOrNull { File(it, "app/build.gradle.kts").isFile }
            ?: error("Could not locate project root")
        return File(root, relativePath)
    }
}
