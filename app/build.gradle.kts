import java.util.Properties
import java.net.URI
import java.util.zip.ZipFile

val alarmQuestAdMobAppId = "ca-app-pub-9163944262143117~4374561480"
val productionRewardedAdUnitId = "ca-app-pub-9163944262143117/6758932696"
val productionArenaRewardedAdUnitId = "ca-app-pub-9163944262143117/1444016513"

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    id("com.google.gms.google-services")
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun configValue(name: String): String =
    localProperties.getProperty(name) ?: System.getenv(name).orEmpty()

fun escapeBuildConfigString(value: String): String =
    value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

fun buildConfigString(name: String): String = escapeBuildConfigString(configValue(name))

fun buildConfigBoolean(name: String, defaultValue: Boolean = false): Boolean =
    configValue(name).ifBlank { defaultValue.toString() }
        .trim()
        .equals("true", ignoreCase = true)

val productionSupabaseUrl = configValue("SUPABASE_URL").trim()
val productionSupabasePublishableKey = configValue("SUPABASE_PUBLISHABLE_KEY").trim()
val arenaQaSupabaseUrl = configValue("SUPABASE_QA_URL").trim()
val arenaQaSupabasePublishableKey = configValue("SUPABASE_QA_PUBLISHABLE_KEY").trim()
val arenaLiveQaSupabaseUrl = configValue("SUPABASE_LIVE_QA_URL").trim()
val arenaLiveQaSupabasePublishableKey = configValue("SUPABASE_LIVE_QA_PUBLISHABLE_KEY").trim()
val arenaLiveServerQaAck = configValue("ARENA_LIVE_SERVER_QA_ACK").trim()
val arenaLiveServerProjectRef = "rlrmaynzdwulbuvymxfa"

fun supabaseProjectRef(url: String, explicitRefName: String): String {
    val explicit = configValue(explicitRefName).trim()
    if (explicit.isNotEmpty()) return explicit
    val host = runCatching { URI(url).host.orEmpty().lowercase() }.getOrDefault("")
    return host.takeIf { it.endsWith(".supabase.co") }
        ?.removeSuffix(".supabase.co")
        .orEmpty()
}

fun checkReleaseRemoteConfiguration() {
    val serverFeatureEnabled =
        buildConfigBoolean("SHARED_PLAYER_SYNC_ENABLED", defaultValue = true) ||
            buildConfigBoolean("ARENA_SERVER_MATCHING_ENABLED", defaultValue = true)
    if (!serverFeatureEnabled) return

    val productionUri = runCatching { URI(productionSupabaseUrl) }.getOrNull()
    check(
        productionUri != null &&
            productionUri.scheme.equals("https", ignoreCase = true) &&
            productionUri.host.equals(
                "$arenaLiveServerProjectRef.supabase.co",
                ignoreCase = true,
            ) &&
            productionUri.userInfo == null &&
            productionUri.port == -1 &&
            (productionUri.path.isNullOrEmpty() || productionUri.path == "/") &&
            productionUri.query == null &&
            productionUri.fragment == null
    ) {
        "SUPABASE_URL must be an exact HTTPS origin when release server features are enabled"
    }
    check(
        productionSupabasePublishableKey.isNotEmpty() &&
            !productionSupabasePublishableKey.startsWith("sb_secret_", ignoreCase = true) &&
            !productionSupabasePublishableKey.contains("service_role", ignoreCase = true)
    ) {
        "SUPABASE_PUBLISHABLE_KEY must be a client publishable key when release server features are enabled"
    }
}

fun checkArenaServerQaCredentials() {
    val qaConfigured = arenaQaSupabaseUrl.isNotEmpty() || arenaQaSupabasePublishableKey.isNotEmpty()
    if (!qaConfigured) return
    check(arenaQaSupabaseUrl.startsWith("https://")) {
        "SUPABASE_QA_URL must be an HTTPS QA endpoint"
    }
    check(arenaQaSupabasePublishableKey.isNotEmpty()) {
        "SUPABASE_QA_PUBLISHABLE_KEY is required when SUPABASE_QA_URL is set"
    }
    check(!arenaQaSupabasePublishableKey.startsWith("sb_secret_", ignoreCase = true) &&
        !arenaQaSupabasePublishableKey.contains("service_role", ignoreCase = true)
    ) { "SUPABASE_QA_PUBLISHABLE_KEY must be a client publishable key" }
    val productionProjectRef = supabaseProjectRef(productionSupabaseUrl, "SUPABASE_PROJECT_REF")
    val qaProjectRef = supabaseProjectRef(arenaQaSupabaseUrl, "SUPABASE_QA_PROJECT_REF")
    check(productionProjectRef.isNotEmpty()) {
        "SUPABASE_URL or SUPABASE_PROJECT_REF is required to prove QA project isolation"
    }
    check(qaProjectRef.isNotEmpty()) {
        "SUPABASE_QA_URL or SUPABASE_QA_PROJECT_REF must identify the QA project"
    }
    check(!arenaQaSupabaseUrl.equals(productionSupabaseUrl, ignoreCase = true)) {
        "Arena server QA refuses the production Supabase URL"
    }
    check(arenaQaSupabasePublishableKey != productionSupabasePublishableKey) {
        "Arena server QA refuses the production Supabase publishable key"
    }
    check(!qaProjectRef.equals(productionProjectRef, ignoreCase = true)) {
        "Arena server QA refuses the production Supabase project ref"
    }
}

fun checkArenaLiveServerQaCredentials() {
    val configured = arenaLiveQaSupabaseUrl.isNotEmpty() ||
        arenaLiveQaSupabasePublishableKey.isNotEmpty() || arenaLiveServerQaAck.isNotEmpty()
    if (!configured) return
    val liveUri = runCatching { URI(arenaLiveQaSupabaseUrl) }.getOrNull()
    check(liveUri != null && liveUri.scheme.equals("https", ignoreCase = true) &&
        liveUri.host.equals("$arenaLiveServerProjectRef.supabase.co", ignoreCase = true) &&
        liveUri.userInfo == null && liveUri.port == -1 &&
        (liveUri.path.isNullOrEmpty() || liveUri.path == "/") &&
        liveUri.query == null && liveUri.fragment == null
    ) {
        "SUPABASE_LIVE_QA_URL must be the exact HTTPS origin of the acknowledged live project"
    }
    check(arenaLiveQaSupabasePublishableKey.isNotEmpty()) {
        "SUPABASE_LIVE_QA_PUBLISHABLE_KEY is required for live Arena QA"
    }
    check(!arenaLiveQaSupabasePublishableKey.startsWith("sb_secret_", ignoreCase = true) &&
        !arenaLiveQaSupabasePublishableKey.contains("service_role", ignoreCase = true)
    ) { "SUPABASE_LIVE_QA_PUBLISHABLE_KEY must be a client publishable key" }
    check(arenaLiveServerQaAck == arenaLiveServerProjectRef) {
        "Live Arena QA requires ARENA_LIVE_SERVER_QA_ACK=$arenaLiveServerProjectRef"
    }
}

fun ByteArray.containsByteSequence(needle: ByteArray): Boolean {
    if (needle.isEmpty() || needle.size > size) return false
    for (start in 0..size - needle.size) {
        var matches = true
        for (offset in needle.indices) {
            if (this[start + offset] != needle[offset]) {
                matches = false
                break
            }
        }
        if (matches) return true
    }
    return false
}

val releaseStoreFile = rootProject.file(
    System.getenv("ALARMQUEST_RELEASE_STORE_FILE")
        ?.takeIf { it.isNotBlank() }
        ?: "keys/alarmquest-upload.keystore",
)
val releasePasswordFile = rootProject.file("keys/alarmquest-upload.pass")
val releaseStorePassword = System.getenv("ALARMQUEST_RELEASE_STORE_PASSWORD")
    ?.takeIf { it.isNotBlank() }
    ?: releasePasswordFile
        .takeIf { it.isFile }
        ?.readText()
        ?.trim()
val releaseKeyPassword = System.getenv("ALARMQUEST_RELEASE_KEY_PASSWORD")
    ?.takeIf { it.isNotBlank() }
    ?: releaseStorePassword
val releaseKeyAlias = System.getenv("ALARMQUEST_RELEASE_KEY_ALIAS")
    ?.takeIf { it.isNotBlank() }
    ?: "alarmquest-upload"

android {
    namespace = "com.nullplaying"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nullplaying"
        minSdk = 26
        targetSdk = 36
        versionCode = 25
        versionName = "0.5.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField(
            "String",
            "ADMOB_APP_ID",
            "\"$alarmQuestAdMobAppId\"",
        )
        manifestPlaceholders["ADMOB_APP_ID"] = alarmQuestAdMobAppId
        buildConfigField(
            "String",
            "BANNER_AD_UNIT_ID",
            "\"ca-app-pub-9163944262143117/1532775725\"",
        )
        buildConfigField(
            "String",
            "REWARDED_AD_UNIT_ID",
            "\"$productionRewardedAdUnitId\"",
        )
        buildConfigField(
            "String",
            "ARENA_REWARDED_AD_UNIT_ID",
            "\"$productionArenaRewardedAdUnitId\"",
        )
        buildConfigField(
            "String",
            "PRIVACY_POLICY_URL",
            "\"https://nullplaying.4ltree.com/privacy\"",
        )
        buildConfigField(
            "String",
            "DATA_DELETION_URL",
            "\"https://nullplaying.4ltree.com/data-deletion\"",
        )
        // UMP geography overrides and consent resets must remain unavailable to production builds.
        buildConfigField("boolean", "IS_EEA_QA", "false")
        buildConfigField("boolean", "REMOTE_SERVICES_ENABLED", "true")
        // Enable only in a release built after the ordered shared-player migration is deployed.
        buildConfigField(
            "boolean",
            "SHARED_PLAYER_SYNC_ENABLED",
            buildConfigBoolean("SHARED_PLAYER_SYNC_ENABLED").toString(),
        )
        buildConfigField("boolean", "SHARED_PLAYER_QA_TRANSPORT_ENABLED", "false")
        buildConfigField("boolean", "ADVENTURE_PREVIEW_ENABLED", "false")
        // Runtime event/relationship/trait rollout. Independent from QA's sequential preview tool.
        buildConfigField(
            "boolean",
            "ADVENTURE_SYSTEM_ENABLED",
            buildConfigBoolean("ADVENTURE_SYSTEM_ENABLED").toString(),
        )
        buildConfigField("boolean", "BATTLE_QA_BRIDGE_ENABLED", "false")
        buildConfigField("boolean", "BATTLE_UNLIMITED_ENTRIES", "false")
        buildConfigField("boolean", "BATTLE_SKILL_TREE_REVIEW_ENABLED", "false")
        buildConfigField("boolean", "BATTLE_IGNORE_HERO_LEVEL_GATE", "false")
        buildConfigField("int", "BATTLE_QA_HERO_LEVEL_OVERRIDE", "0")
        // Server-backed Arena matching is prepared independently from shared-player sync and
        // remains OFF until the shared roster migration and gateway controls are deployed.
        buildConfigField(
            "boolean",
            "ARENA_SERVER_MATCHING_ENABLED",
            buildConfigBoolean("ARENA_SERVER_MATCHING_ENABLED").toString(),
        )
        buildConfigField("String", "BATTLE_QA_BRIDGE_URL", "\"\"")
        buildConfigField("String", "UMP_TEST_DEVICE_HASH", "\"\"")
        buildConfigField("String", "SUPABASE_URL", "\"${buildConfigString("SUPABASE_URL")}\"")
        buildConfigField(
            "String",
            "SUPABASE_PUBLISHABLE_KEY",
            "\"${buildConfigString("SUPABASE_PUBLISHABLE_KEY")}\"",
        )
        buildConfigField("String", "SUPABASE_QA_URL", "\"\"")
        buildConfigField("String", "SUPABASE_QA_PUBLISHABLE_KEY", "\"\"")
        buildConfigField("String", "ARENA_LIVE_SERVER_QA_ACK", "\"\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            storeFile = releaseStoreFile
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
        }
    }

    buildTypes {
        getByName("debug") {
            // QA must never create anonymous users, profiles, rankings, or session logs in production.
            // Firebase Remote Config remains enabled independently of Supabase.
            buildConfigField("String", "SUPABASE_URL", "\"\"")
            buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", "\"\"")
            buildConfigField("boolean", "SHARED_PLAYER_SYNC_ENABLED", "false")
            buildConfigField("boolean", "ADVENTURE_SYSTEM_ENABLED", "false")
            buildConfigField("boolean", "ARENA_SERVER_MATCHING_ENABLED", "false")
            // Keep the installable QA build below the Drive/device delivery ceiling while
            // retaining debug-only inspection screens and debug signing.
            isMinifyEnabled = true
            isShrinkResources = true
            // Never request live ads from local QA builds. This protects the AdMob account
            // from accidental invalid traffic while exercising each configured ad format.
            buildConfigField(
                "String",
                "BANNER_AD_UNIT_ID",
                "\"ca-app-pub-3940256099942544/9214589741\"",
            )
            buildConfigField(
                "String",
                "REWARDED_AD_UNIT_ID",
                "\"ca-app-pub-3940256099942544/5224354917\"",
            )
            buildConfigField(
                "String",
                "ARENA_REWARDED_AD_UNIT_ID",
                "\"ca-app-pub-3940256099942544/5224354917\"",
            )
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
        create("migrationTest") {
            initWith(getByName("debug"))
            isMinifyEnabled = false
            isShrinkResources = false
            matchingFallbacks += listOf("debug")
            // Instrumented Room migration tests use the production package ID so they must be
            // fully local even on a dedicated emulator. Keep every remote/ad SDK startup gate
            // closed independently of the debug build type they inherit from.
            buildConfigField("boolean", "REMOTE_SERVICES_ENABLED", "false")
            buildConfigField("String", "ADMOB_APP_ID", "\"\"")
            buildConfigField("String", "BANNER_AD_UNIT_ID", "\"\"")
            buildConfigField("String", "REWARDED_AD_UNIT_ID", "\"\"")
            buildConfigField("String", "ARENA_REWARDED_AD_UNIT_ID", "\"\"")
        }
        create("eeaQa") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".eeaqa"
            versionNameSuffix = "-eea-qa"
            matchingFallbacks += listOf("debug")
            resValue("string", "app_name", "AlarmQuest EEA QA")
            buildConfigField("boolean", "IS_EEA_QA", "true")
            buildConfigField(
                "String",
                "UMP_TEST_DEVICE_HASH",
                "\"${buildConfigString("ALARMQUEST_UMP_TEST_DEVICE_HASH")}\"",
            )
        }
        create("offlineQa") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".adventurepreview"
            isMinifyEnabled = false
            isShrinkResources = false
            versionNameSuffix = "-offline-qa"
            matchingFallbacks += listOf("debug")
            resValue("string", "app_name", "NULL PLAYING 모험 검증")
            buildConfigField("boolean", "ADVENTURE_PREVIEW_ENABLED", "true")
            buildConfigField("boolean", "ADVENTURE_SYSTEM_ENABLED", "true")
            // Defense in depth: the variant manifest also removes INTERNET and network-state
            // permissions, while runtime gates avoid initializing any remote SDK or service.
            buildConfigField("boolean", "REMOTE_SERVICES_ENABLED", "false")
            buildConfigField("boolean", "ARENA_SERVER_MATCHING_ENABLED", "false")
            buildConfigField("String", "SUPABASE_URL", "\"\"")
            buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", "\"\"")
            buildConfigField("String", "ADMOB_APP_ID", "\"\"")
            buildConfigField("String", "BANNER_AD_UNIT_ID", "\"\"")
            buildConfigField("String", "REWARDED_AD_UNIT_ID", "\"\"")
            buildConfigField("String", "ARENA_REWARDED_AD_UNIT_ID", "\"\"")
        }
        create("battleQa") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".battleqa"
            versionNameSuffix = "-battle-qa"
            matchingFallbacks += listOf("debug")
            isMinifyEnabled = false
            isShrinkResources = false
            resValue("string", "app_name", "AlarmQuest Arena QA")
            // Battle QA is fully local. It cannot initialize Supabase, Firebase, ads,
            // or the retired host-side narrative bridge.
            buildConfigField("boolean", "REMOTE_SERVICES_ENABLED", "false")
            buildConfigField("String", "SUPABASE_URL", "\"\"")
            buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", "\"\"")
            buildConfigField("boolean", "BATTLE_QA_BRIDGE_ENABLED", "false")
            buildConfigField("boolean", "BATTLE_UNLIMITED_ENTRIES", "false")
            buildConfigField("boolean", "BATTLE_SKILL_TREE_REVIEW_ENABLED", "true")
            buildConfigField("boolean", "BATTLE_IGNORE_HERO_LEVEL_GATE", "false")
            buildConfigField("int", "BATTLE_QA_HERO_LEVEL_OVERRIDE", "0")
            buildConfigField("boolean", "ARENA_SERVER_MATCHING_ENABLED", "false")
            buildConfigField("String", "ADMOB_APP_ID", "\"\"")
            buildConfigField("String", "BANNER_AD_UNIT_ID", "\"\"")
            buildConfigField("String", "REWARDED_AD_UNIT_ID", "\"\"")
            buildConfigField("String", "ARENA_REWARDED_AD_UNIT_ID", "\"\"")
            buildConfigField("String", "BATTLE_QA_BRIDGE_URL", "\"\"")
        }
        // Side-by-side, offline arena sandbox. None of these controls ship in release.
        create("arenaLab") {
            initWith(getByName("battleQa"))
            applicationIdSuffix = ".arenalab"
            versionNameSuffix = "-arena-qa-mp40"
            resValue("string", "app_name", "결투장 QA")
            buildConfigField("boolean", "BATTLE_UNLIMITED_ENTRIES", "true")
            buildConfigField("boolean", "BATTLE_SKILL_TREE_REVIEW_ENABLED", "false")
            buildConfigField("boolean", "SHARED_PLAYER_SYNC_ENABLED", "false")
        }
        create("arenaServerQa") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".arenaserverqa"
            versionNameSuffix = "-arena-server-qa"
            matchingFallbacks += listOf("debug")
            isMinifyEnabled = false
            isShrinkResources = false
            resValue("string", "app_name", "AlarmQuest Arena Server QA")
            // The normal remote stack stays off. Only the dedicated shared-player transport may
            // use the separately supplied QA project after its destination has been validated.
            buildConfigField("boolean", "REMOTE_SERVICES_ENABLED", "false")
            buildConfigField("boolean", "SHARED_PLAYER_QA_TRANSPORT_ENABLED", "true")
            buildConfigField("boolean", "SHARED_PLAYER_SYNC_ENABLED", "true")
            buildConfigField("boolean", "ADVENTURE_SYSTEM_ENABLED", "true")
            buildConfigField("boolean", "ARENA_SERVER_MATCHING_ENABLED", "true")
            buildConfigField("String", "SUPABASE_URL", "\"\"")
            buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", "\"\"")
            buildConfigField(
                "String",
                "SUPABASE_QA_URL",
                "\"${escapeBuildConfigString(arenaQaSupabaseUrl)}\"",
            )
            buildConfigField(
                "String",
                "SUPABASE_QA_PUBLISHABLE_KEY",
                "\"${escapeBuildConfigString(arenaQaSupabasePublishableKey)}\"",
            )
            buildConfigField("boolean", "BATTLE_QA_BRIDGE_ENABLED", "false")
            buildConfigField("String", "BATTLE_QA_BRIDGE_URL", "\"\"")
            buildConfigField("String", "ADMOB_APP_ID", "\"\"")
            buildConfigField("String", "BANNER_AD_UNIT_ID", "\"\"")
            buildConfigField("String", "REWARDED_AD_UNIT_ID", "\"\"")
            buildConfigField("String", "ARENA_REWARDED_AD_UNIT_ID", "\"\"")
        }
        create("arenaLiveServerQa") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".arenaliveserverqa"
            versionNameSuffix = "-arena-live-server-qa"
            matchingFallbacks += listOf("debug")
            isMinifyEnabled = false
            isShrinkResources = false
            resValue("string", "app_name", "AlarmQuest Arena Live QA")
            // This side-by-side package has one purpose: the explicitly acknowledged live
            // shared-player RPC test. The normal remote stack remains unreachable.
            buildConfigField("boolean", "REMOTE_SERVICES_ENABLED", "false")
            buildConfigField("boolean", "SHARED_PLAYER_QA_TRANSPORT_ENABLED", "true")
            buildConfigField("boolean", "SHARED_PLAYER_SYNC_ENABLED", "true")
            buildConfigField("boolean", "ADVENTURE_SYSTEM_ENABLED", "true")
            buildConfigField("boolean", "ARENA_SERVER_MATCHING_ENABLED", "true")
            buildConfigField("String", "SUPABASE_URL", "\"\"")
            buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", "\"\"")
            buildConfigField(
                "String",
                "SUPABASE_QA_URL",
                "\"${escapeBuildConfigString(arenaLiveQaSupabaseUrl)}\"",
            )
            buildConfigField(
                "String",
                "SUPABASE_QA_PUBLISHABLE_KEY",
                "\"${escapeBuildConfigString(arenaLiveQaSupabasePublishableKey)}\"",
            )
            buildConfigField(
                "String",
                "ARENA_LIVE_SERVER_QA_ACK",
                "\"${escapeBuildConfigString(arenaLiveServerQaAck)}\"",
            )
            buildConfigField("boolean", "BATTLE_QA_BRIDGE_ENABLED", "false")
            buildConfigField("String", "BATTLE_QA_BRIDGE_URL", "\"\"")
            buildConfigField("String", "ADMOB_APP_ID", "\"\"")
            buildConfigField("String", "BANNER_AD_UNIT_ID", "\"\"")
            buildConfigField("String", "REWARDED_AD_UNIT_ID", "\"\"")
            buildConfigField("String", "ARENA_REWARDED_AD_UNIT_ID", "\"\"")
        }
        getByName("release") {
            // Shared-player migrations 004..008 are deployed. Explicit false remains a rollout
            // override, while a normal release build includes the reviewed Adventure, bond, and
            // server Arena features without depending on an unrecorded local environment value.
            buildConfigField(
                "boolean",
                "SHARED_PLAYER_SYNC_ENABLED",
                buildConfigBoolean("SHARED_PLAYER_SYNC_ENABLED", defaultValue = true).toString(),
            )
            buildConfigField(
                "boolean",
                "ARENA_SERVER_MATCHING_ENABLED",
                buildConfigBoolean("ARENA_SERVER_MATCHING_ENABLED", defaultValue = true).toString(),
            )
            buildConfigField(
                "boolean",
                "ADVENTURE_SYSTEM_ENABLED",
                buildConfigBoolean("ADVENTURE_SYSTEM_ENABLED", defaultValue = true).toString(),
            )
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    testBuildType = "migrationTest"

    sourceSets {
        getByName("main") {
            java.setSrcDirs(listOf("src/simple/java"))
            assets.setSrcDirs(emptyList<String>())
            res.setSrcDirs(listOf("src/simple/res"))
        }
        getByName("test") {
            java.setSrcDirs(listOf("src/simpleTest/java"))
        }
        getByName("androidTest") {
            assets.setSrcDirs(listOf("schemas"))
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// The side-by-side EEA QA package intentionally has no Firebase registration.
// Remote Config already falls back safely when Firebase configuration is absent.
tasks.matching { it.name == "processEeaQaGoogleServices" }.configureEach {
    enabled = false
}

// Migration tests inherit the debug no-remote contract and must not require Firebase setup.
tasks.matching { it.name == "processMigrationTestGoogleServices" }.configureEach {
    enabled = false
}

// The fully offline QA package is intentionally absent from Firebase and cannot fetch config.
tasks.matching { it.name == "processOfflineQaGoogleServices" }.configureEach {
    enabled = false
}

// Battle QA talks only to the host-side loopback bridge and has no Firebase app.
tasks.matching { it.name == "processBattleQaGoogleServices" }.configureEach {
    enabled = false
}

tasks.matching { it.name == "processArenaLabGoogleServices" }.configureEach {
    enabled = false
}

// Arena Server QA retains INTERNET for its narrow transport but never initializes Firebase/ads.
tasks.matching { it.name == "processArenaServerQaGoogleServices" }.configureEach {
    enabled = false
}

// The explicit live QA package also excludes every Firebase/ads initializer.
tasks.matching { it.name == "processArenaLiveServerQaGoogleServices" }.configureEach {
    enabled = false
}

val validateArenaServerQaCredentials by tasks.registering {
    group = "verification"
    description = "Rejects production or malformed credentials from the Arena Server QA build"
    doLast { checkArenaServerQaCredentials() }
}

tasks.matching {
    it.name.contains("ArenaServerQa") && it.name != "validateArenaServerQaCredentials"
}.configureEach {
    dependsOn(validateArenaServerQaCredentials)
}

val validateArenaLiveServerQaCredentials by tasks.registering {
    group = "verification"
    description = "Requires the exact live project acknowledgement for Arena Live Server QA"
    doLast { checkArenaLiveServerQaCredentials() }
}

tasks.matching {
    it.name.contains("ArenaLiveServerQa") && it.name != "validateArenaLiveServerQaCredentials"
}.configureEach {
    dependsOn(validateArenaLiveServerQaCredentials)
}

tasks.matching { it.name == "assembleArenaServerQa" }.configureEach {
    doLast {
        val forbiddenValues = listOf(productionSupabaseUrl, productionSupabasePublishableKey)
            .filter(String::isNotEmpty)
            .map { it.toByteArray(Charsets.UTF_8) }
        if (forbiddenValues.isEmpty()) return@doLast
        val apks = fileTree(layout.buildDirectory.dir("outputs/apk/arenaServerQa")) {
            include("**/*.apk")
        }.files
        check(apks.isNotEmpty()) { "Arena Server QA APK was not found for credential audit" }
        apks.forEach { apk ->
            ZipFile(apk).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue
                    val bytes = zip.getInputStream(entry).use { it.readBytes() }
                    check(forbiddenValues.none { forbidden ->
                        bytes.containsByteSequence(forbidden)
                    }) {
                        "Arena Server QA artifact contains a production Supabase credential"
                    }
                }
            }
        }
    }
}

val validateReleaseRemoteConfiguration by tasks.registering {
    group = "verification"
    description = "Requires a safe production Supabase client configuration for release server features"
    doLast { checkReleaseRemoteConfiguration() }
}

tasks.matching {
    val taskName = it.name.lowercase()
    taskName.contains("release") &&
        listOf("assemble", "bundle", "lint", "package").any(taskName::startsWith)
}.configureEach {
    dependsOn(validateReleaseRemoteConfiguration)
}

// Sample size must invalidate cached balance-test results and reach the forked test JVM.
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    val arenaTalentSeeds = providers.environmentVariable("ARENA_TALENT_SEEDS_PER_CELL").orElse("1")
    inputs.property("arenaTalent.seedsPerCell", arenaTalentSeeds)
    environment("ARENA_TALENT_SEEDS_PER_CELL", arenaTalentSeeds.get())
    for ((name, fallback) in mapOf(
        "IDENTITY_BALANCE_SEEDS" to "128",
        "IDENTITY_BALANCE_OFFSET" to "0",
        "IDENTITY_BALANCE_LEVELS" to "10,14,15,20,25,30,50,100",
        "ARENA_REPORTED_FIXTURE_DIR" to "",
    )) {
        val value=providers.environmentVariable(name).orElse(fallback)
        inputs.property("arenaIdentity.$name",value)
        environment(name,value.get())
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(platform("com.google.firebase:firebase-bom:34.2.0"))
    implementation("com.google.firebase:firebase-config")
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.fragment:fragment:1.9.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.work:work-runtime:2.7.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("com.google.android.libraries.ads.mobile.sdk:ads-mobile-sdk:1.4.0")
    implementation("com.google.android.ump:user-messaging-platform:4.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    ksp("androidx.room:room-compiler:2.8.4")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    androidTestImplementation("androidx.room:room-testing:2.8.4")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
