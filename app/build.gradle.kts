plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ai.djwizard.tvbridge"
    compileSdk = 34

    defaultConfig {
        applicationId = "ai.djwizard.tvbridge"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        // Pass runtime config through BuildConfig. -P values from gradle.properties
        // or the command line win; empty string means "prompt the user at runtime".
        val relayUrl: String = (findProperty("relayUrl") as? String) ?: "https://tv.djwizard.ai"
        val bridgeToken: String = (findProperty("bridgeToken") as? String) ?: ""
        val deviceId: String = (findProperty("deviceId") as? String) ?: "emu-1"
        buildConfigField("String", "RELAY_URL", "\"$relayUrl\"")
        buildConfigField("String", "BRIDGE_TOKEN", "\"$bridgeToken\"")
        buildConfigField("String", "DEVICE_ID", "\"$deviceId\"")
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
