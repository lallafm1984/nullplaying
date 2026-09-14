package com.nullplaying

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseCandidateBuildContractTest {
    private val buildScript by lazy { projectFile("app/build.gradle.kts").readText() }

    @Test
    fun `release candidate keeps the reviewed store version`() {
        val defaultConfig = buildScript.section(
            start = "defaultConfig {",
            end = "buildFeatures {",
        )

        assertTrue(defaultConfig.contains(Regex("""versionCode\s*=\s*27\b""")))
        assertTrue(defaultConfig.contains(Regex("versionName\\s*=\\s*\\\"0\\.5\\.2\\\"")))
    }

    @Test
    fun `release keeps integrated resource shrinking and native crash metadata`() {
        val release = buildScript.section(
            start = "getByName(\"release\") {",
            end = "testBuildType = \"migrationTest\"",
        )
        val gradleProperties = projectFile("gradle.properties").readText()

        assertTrue(release.contains("isMinifyEnabled = true"))
        assertTrue(release.contains("isShrinkResources = true"))
        assertTrue(release.contains("debugSymbolLevel = \"SYMBOL_TABLE\""))
        assertTrue(gradleProperties.contains("android.r8.optimizedResourceShrinking=true"))
    }

    @Test
    fun `release enables the three reviewed systems unless explicitly disabled`() {
        val booleanResolver = buildScript.section(
            start = "fun buildConfigBoolean(",
            end = "val productionSupabaseUrl",
        ).normalizedWhitespace()
        assertTrue(booleanResolver.contains("defaultValue: Boolean = false"))
        assertTrue(
            booleanResolver.contains(
                "configValue(name).ifBlank { defaultValue.toString() } .trim() " +
                    ".equals(\"true\", ignoreCase = true)",
            ),
        )

        val release = buildScript.section(
            start = "getByName(\"release\") {",
            end = "testBuildType = \"migrationTest\"",
        )
        RELEASE_FEATURE_FLAGS.forEach { flag ->
            assertTrue(
                "$flag must default to true in release",
                Regex(
                    """buildConfigBoolean\("$flag",\s*defaultValue\s*=\s*true\)\.toString\(\)""",
                ).containsMatchIn(release),
            )
            assertFalse(
                "$flag must retain the shared resolver so an explicit false can disable it",
                Regex("buildConfigField\\(\\s*\\\"boolean\\\",\\s*\\\"$flag\\\",\\s*\\\"true\\\"")
                    .containsMatchIn(release),
            )
        }
    }

    @Test
    fun `release artifact tasks fail fast on unsafe remote client configuration`() {
        val validator = buildScript.section(
            start = "fun checkReleaseRemoteConfiguration() {",
            end = "fun checkArenaServerQaCredentials() {",
        ).normalizedWhitespace()
        assertTrue(
            validator.contains(
                "buildConfigBoolean(\"SHARED_PLAYER_SYNC_ENABLED\", defaultValue = true) || " +
                    "buildConfigBoolean(\"ARENA_SERVER_MATCHING_ENABLED\", defaultValue = true)",
            ),
        )
        assertTrue(validator.contains("if (!serverFeatureEnabled) return"))
        listOf(
            "productionUri.scheme.equals(\"https\", ignoreCase = true)",
            "productionUri.host.equals( \"\$arenaLiveServerProjectRef.supabase.co\", ignoreCase = true, )",
            "productionUri.userInfo == null",
            "productionUri.port == -1",
            "productionUri.path.isNullOrEmpty() || productionUri.path == \"/\"",
            "productionUri.query == null",
            "productionUri.fragment == null",
        ).forEach { exactOriginGuard ->
            assertTrue("missing release URL guard: $exactOriginGuard", validator.contains(exactOriginGuard))
        }
        assertTrue(validator.contains("productionSupabasePublishableKey.isNotEmpty()"))
        assertTrue(
            validator.contains(
                "!productionSupabasePublishableKey.startsWith(\"sb_secret_\", ignoreCase = true)",
            ),
        )
        assertTrue(
            validator.contains(
                "!productionSupabasePublishableKey.contains(\"service_role\", ignoreCase = true)",
            ),
        )
        assertFalse(validator.contains("\$productionSupabaseUrl"))
        assertFalse(validator.contains("\$productionSupabasePublishableKey"))

        val taskWiring = buildScript.section(
            start = "val validateReleaseRemoteConfiguration by tasks.registering {",
            end = "// Sample size must invalidate cached balance-test results",
        ).normalizedWhitespace()
        assertTrue(taskWiring.contains("doLast { checkReleaseRemoteConfiguration() }"))
        assertTrue(taskWiring.contains("taskName.contains(\"release\")"))
        listOf("assemble", "bundle", "lint", "package").forEach { prefix ->
            assertTrue("release $prefix tasks must be gated", taskWiring.contains("\"$prefix\""))
        }
        assertTrue(taskWiring.contains("dependsOn(validateReleaseRemoteConfiguration)"))
    }

    @Test
    fun `local QA variants remain isolated from release services`() {
        val debug = buildScript.section(
            start = "getByName(\"debug\") {",
            end = "create(\"migrationTest\") {",
        )
        RELEASE_FEATURE_FLAGS.forEach { flag ->
            assertTrue(debug.containsBooleanField(flag, value = false))
        }
        assertTrue(debug.containsEmptyStringField("SUPABASE_URL"))
        assertTrue(debug.containsEmptyStringField("SUPABASE_PUBLISHABLE_KEY"))

        val migrationTest = buildScript.section(
            start = "create(\"migrationTest\") {",
            end = "create(\"eeaQa\") {",
        )
        assertTrue(migrationTest.contains("initWith(getByName(\"debug\"))"))
        assertTrue(migrationTest.containsBooleanField("REMOTE_SERVICES_ENABLED", value = false))
        OFFLINE_AD_FIELDS.forEach { field ->
            assertTrue("migrationTest must blank $field", migrationTest.containsEmptyStringField(field))
        }

        val offlineQa = buildScript.section(
            start = "create(\"offlineQa\") {",
            end = "create(\"battleQa\") {",
        )
        assertTrue(offlineQa.contains("initWith(getByName(\"debug\"))"))
        assertTrue(offlineQa.containsBooleanField("REMOTE_SERVICES_ENABLED", value = false))
        assertTrue(offlineQa.containsBooleanField("ARENA_SERVER_MATCHING_ENABLED", value = false))
        assertTrue(offlineQa.containsBooleanField("ADVENTURE_SYSTEM_ENABLED", value = true))
        assertFalse(offlineQa.containsBooleanField("SHARED_PLAYER_SYNC_ENABLED", value = true))
        OFFLINE_SECRET_FIELDS.forEach { field ->
            assertTrue("offlineQa must blank $field", offlineQa.containsEmptyStringField(field))
        }

        val battleQa = buildScript.section(
            start = "create(\"battleQa\") {",
            end = "create(\"arenaServerQa\") {",
        )
        assertTrue(battleQa.contains("initWith(getByName(\"debug\"))"))
        assertTrue(battleQa.containsBooleanField("REMOTE_SERVICES_ENABLED", value = false))
        assertTrue(battleQa.containsBooleanField("ARENA_SERVER_MATCHING_ENABLED", value = false))
        assertFalse(battleQa.containsBooleanField("SHARED_PLAYER_SYNC_ENABLED", value = true))
        assertFalse(battleQa.containsBooleanField("ADVENTURE_SYSTEM_ENABLED", value = true))
        OFFLINE_SECRET_FIELDS.forEach { field ->
            assertTrue("battleQa must blank $field", battleQa.containsEmptyStringField(field))
        }
    }

    @Test
    fun `migration test manifest removes remote permissions and SDK startup components`() {
        val manifest = projectFile("app/src/migrationTest/AndroidManifest.xml").readText()

        listOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "com.google.android.gms.permission.AD_ID",
            "android.permission.READ_BASIC_PHONE_STATE",
            "com.google.android.gms.ads.APPLICATION_ID",
            "com.google.firebase.provider.FirebaseInitProvider",
            "com.google.firebase.components.ComponentDiscoveryService",
            "com.google.android.libraries.ads.mobile.sdk.common.AdActivity",
            "com.google.android.gms.common.api.GoogleApiActivity",
        ).forEach { removedEntry ->
            val entry = manifest.substringBefore(removedEntry).substringAfterLast('<') +
                removedEntry + manifest.substringAfter(removedEntry).substringBefore('>')
            assertTrue("migrationTest must declare removal for $removedEntry", entry.contains("tools:node=\"remove\""))
        }
    }

    private fun String.section(start: String, end: String): String {
        require(contains(start)) { "Missing source marker: $start" }
        val remainder = substringAfter(start)
        require(remainder.contains(end)) { "Missing source marker after $start: $end" }
        return remainder.substringBefore(end)
    }

    private fun String.normalizedWhitespace(): String = replace(Regex("\\s+"), " ").trim()

    private fun String.containsBooleanField(name: String, value: Boolean): Boolean =
        Regex(
            """buildConfigField\(\s*"boolean",\s*"$name",\s*"${value}"\s*\)""",
        ).containsMatchIn(this)

    private fun String.containsEmptyStringField(name: String): Boolean =
        Regex(
            """buildConfigField\(\s*"String",\s*"$name",\s*${Regex.escape("\"\\\"\\\"\"")}\s*,?\s*\)""",
        ).containsMatchIn(this)

    private fun projectFile(relativePath: String): File {
        val workingDirectory = requireNotNull(System.getProperty("user.dir"))
        val root = generateSequence(File(workingDirectory).absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "app/build.gradle.kts").isFile }
            ?: error("Could not locate project root")
        return File(root, relativePath)
    }

    private companion object {
        val RELEASE_FEATURE_FLAGS = listOf(
            "SHARED_PLAYER_SYNC_ENABLED",
            "ARENA_SERVER_MATCHING_ENABLED",
            "ADVENTURE_SYSTEM_ENABLED",
        )
        val OFFLINE_AD_FIELDS = listOf(
            "ADMOB_APP_ID",
            "BANNER_AD_UNIT_ID",
            "REWARDED_AD_UNIT_ID",
            "ARENA_REWARDED_AD_UNIT_ID",
        )
        val OFFLINE_SECRET_FIELDS = listOf(
            "SUPABASE_URL",
            "SUPABASE_PUBLISHABLE_KEY",
        ) + OFFLINE_AD_FIELDS
    }
}
