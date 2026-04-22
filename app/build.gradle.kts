plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ai.djwizard.tvbridge"
    compileSdk = 35

    defaultConfig {
        applicationId = "ai.djwizard.tvbridge"
        minSdk = 23
        targetSdk = 35
        versionCode = 9
        versionName = "0.5.3"

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

    // Release signing — credentials come from gradle.properties.local (gitignored)
    // or -P flags at the build command. When unset, release builds fall back to
    // debug signing so the build still produces an installable APK for dev.
    // Production releases MUST come from a keystore-signed build; Play Protect
    // flags debug-signed APKs as "potentially harmful."
    val keystorePath = (findProperty("tvwizardKeystorePath") as? String).orEmpty()
    val keystorePass = (findProperty("tvwizardKeystorePass") as? String).orEmpty()
    val keyAliasName = (findProperty("tvwizardKeyAlias") as? String) ?: "tvwizard"
    val keyPass = (findProperty("tvwizardKeyPass") as? String).orEmpty()
    val hasReleaseKeystore = keystorePath.isNotBlank() && keystorePass.isNotBlank()

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = keystorePass
                keyAlias = keyAliasName
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // QR code renderer — core only, no Android-embedded layer. See QrCode.kt.
    implementation("com.google.zxing:core:3.5.3")
}
