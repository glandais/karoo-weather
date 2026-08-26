import java.io.FileInputStream
import java.util.Properties

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
