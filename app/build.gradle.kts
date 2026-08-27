import java.io.FileInputStream
import java.util.Properties

val distributionBaseUrl: String =
    System.getenv("KAROO_BASE_URL")
        ?: "https://github.com/glandais/karoo-weather/releases/latest/download"

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val keystorePropertiesFile: File = rootProject.file("keystore.properties")
val keystoreProperties = Properties()

if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "io.github.glandais.karoo.weather"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.glandais.karoo.weather"
        minSdk = 26
        // Karoo runs Android 12 -> targetSdk must be 32
        //noinspection ExpiredTargetSdkVersion
        targetSdk = 32
        versionCode = System.getenv("KAROO_VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("KAROO_VERSION_NAME") ?: "1.0.0"

        // Injected into <meta-data io.hammerhead.karooext.MANIFEST_URL>. The manifest is never
        // rewritten in place at build time (PLAN WP8).
        manifestPlaceholders["karooManifestUrl"] = "$distributionBaseUrl/manifest.json"
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
            }
        }
    }

    buildTypes {
        release {
            signingConfig =
                if (keystorePropertiesFile.exists()) signingConfigs.getByName("release") else null
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions { unitTests.isReturnDefaultValues = true }
}

/**
 * Writes `app/manifest.json`, the file the Karoo extension store polls to discover new releases.
 * Runs before every `assemble`.
 */
tasks.register("generateManifest") {
    description = "Generates manifest.json with current version information"
    group = "build"

    val outputFile = layout.projectDirectory.file("manifest.json")
    val baseUrl = distributionBaseUrl
    val label = "Weather"
    val pkg = "io.github.glandais.karoo.weather"
    val versionName = android.defaultConfig.versionName
    val versionCode = android.defaultConfig.versionCode
    // Set by the release workflow from the annotated tag's message. Read through System.getenv,
    // which Gradle instruments as a configuration-cache input; a plain File read at configuration
    // time would not be tracked.
    val releaseNotes =
        System.getenv("KAROO_RELEASE_NOTES")?.trim()?.takeIf { it.isNotEmpty() }
            ?: "Release $versionName"

    // Inputs must be declared: a task with outputs and no inputs is UP-TO-DATE forever, which
    // would ship a stale manifest.json whenever only the version changed.
    inputs.property("baseUrl", baseUrl)
    inputs.property("label", label)
    inputs.property("packageName", pkg)
    inputs.property("versionName", versionName)
    inputs.property("versionCode", versionCode)
    inputs.property("releaseNotes", releaseNotes)
    outputs.file(outputFile)

    doLast {
        val manifest =
            mapOf(
                "label" to label,
                "packageName" to pkg,
                "latestApkUrl" to "$baseUrl/app-release.apk",
                "latestVersion" to versionName,
                "latestVersionCode" to versionCode,
                "developer" to "github.com/glandais",
                "description" to
                    "Weather now, wind relative to your heading, rain for the next two hours and " +
                        "a forecast along your route at your estimated arrival time. " +
                        "Weather data by Open-Meteo.com (CC BY 4.0).",
                "releaseNotes" to releaseNotes,
                "tags" to listOf("weather"),
            )
        outputFile.asFile.writeText(groovy.json.JsonBuilder(manifest).toPrettyString())
    }
}

// Hooked to preBuild, not `assemble`: `assembleDebug`/`assembleRelease` do not depend on
// `assemble`, and the file must exist for every build that could be published.
tasks.named("preBuild") { dependsOn("generateManifest") }

dependencies {
    // Karoo SDK
    implementation(libs.karoo.ext)

    // Kotlin Serialization
    implementation(libs.kotlinx.serialization.json)

    // Compose BOM
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    // Glance (RemoteViews for data fields)
    implementation(libs.glance.appwidget)
    implementation(libs.glance.preview)
    implementation(libs.glance.appwidget.preview)

    // AndroidX
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.activity.compose)

    // DataStore for settings
    implementation(libs.datastore)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlinx.coroutines.test)
}
