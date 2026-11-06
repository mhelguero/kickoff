plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "app.kickoff"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.kickoff"
        minSdk = 26
        targetSdk = 35
        // CI passes the GitHub run number so every build installs as an update.
        versionCode = (findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = "1.$versionCode"
        // For finding game highlights on YouTube; CI passes the repo secret, local builds go without.
        buildConfigField("String", "YOUTUBE_API_KEY", "\"${System.getenv("YOUTUBE_API_KEY").orEmpty()}\"")
    }

    // The same key must sign every build, or Android refuses to install it over the previous one.
    // CI provides it from repo secrets; local builds fall back to the debug key.
    val keystore = System.getenv("KEYSTORE_PATH")
    signingConfigs {
        if (keystore != null) create("release") {
            storeFile = file(keystore)
            storeType = "pkcs12"
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = "kickoff"
            keyPassword = System.getenv("KEYSTORE_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    lint {
        checkReleaseBuilds = false
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jsoup:jsoup:1.18.3")   // reads futbolenvivobolivia.com's listings
}
