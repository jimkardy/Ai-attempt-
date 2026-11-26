plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.aiattempt.debroid"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aiattempt.debroid"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.1.1"
    }

    // The debug builds published on the `debugging` branch are signed with a throwaway
    // test key that is committed to the repo (test-only, NOT a production identity).
    // Every build therefore upgrades over the previous one instead of failing with
    // "App not installed" / "package conflicts".
    signingConfigs {
        create("testKey") {
            storeFile = rootProject.file("keystore/debroid-test.keystore")
            storePassword = "debroidtest"
            keyAlias = "debroidtest"
            keyPassword = "debroidtest"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("testKey")
            isMinifyEnabled = false
        }
        getByName("release") {
            signingConfig = signingConfigs.getByName("testKey")
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
