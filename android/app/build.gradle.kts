plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.chaquo.python")
}

android {
    namespace = "local.solwatch.pocket"
    compileSdk = 36

    defaultConfig {
        applicationId = "local.solwatch.pocket"
        minSdk = 29
        targetSdk = 36
        versionCode = 16
        versionName = "0.7.1"
        // No key is ever built in (since 0.4.0): the user pastes theirs on the Status screen.
        buildConfigField("String", "TEST_API_KEY", "\"\"")
        ndk {
            // Phones only: keeps the APK small and the build quick.
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            // R8 shrinks the unused Compose code: the debug APK is ~38 MB, mostly dex.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Test builds only, until we make a real signing key.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        jniLibs {
            // Compress the native libraries (mostly Python's): a much smaller APK
            // for one extraction on install.
            useLegacyPackaging = true
        }
    }
}

kotlin {
    jvmToolchain(17)
}

chaquopy {
    defaultConfig {
        version = "3.13"
        buildPython("/usr/bin/python3")
        pip {
            // Same requests as the desktop tests on MiniTestPC. tzdata: Android has no
            // /usr/share/zoneinfo, and the bridge needs it for IRL_TIMEZONE.
            install("requests==2.32.3")
            install("tzdata")
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.12.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.12.2")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.glance:glance-appwidget:1.1.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")  // the real org.json, for parsing in JVM tests
}
