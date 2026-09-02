import java.util.Properties

val alarmQuestAdMobAppId = "ca-app-pub-9163944262143117~4374561480"

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

fun buildConfigString(name: String): String =
    (localProperties.getProperty(name) ?: System.getenv(name).orEmpty())
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

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
        versionCode = 15
        versionName = "0.4.2"
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
            "\"ca-app-pub-9163944262143117/2295193051\"",
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
        buildConfigField("String", "UMP_TEST_DEVICE_HASH", "\"\"")
        buildConfigField("String", "SUPABASE_URL", "\"${buildConfigString("SUPABASE_URL")}\"")
        buildConfigField(
            "String",
            "SUPABASE_PUBLISHABLE_KEY",
            "\"${buildConfigString("SUPABASE_PUBLISHABLE_KEY")}\"",
        )
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
                "\"ca-app-pub-3940256099942544/5354046379\"",
            )
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
        create("migrationTest") {
            initWith(getByName("debug"))
            isMinifyEnabled = false
            isShrinkResources = false
            matchingFallbacks += listOf("debug")
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
        getByName("release") {
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
