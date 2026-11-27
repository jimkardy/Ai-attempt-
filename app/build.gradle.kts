plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.aiattempt.debroid"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aiattempt.debroid"
        // Android 10 is the floor: arm64 phones that support UML are 10+.
        minSdk = 29
        targetSdk = 35
        versionCode = 8
        versionName = "0.3.3"

        // Debroid is arm64-v8a ONLY. The UML kernel and the Debian guest are
        // aarch64; a 32-bit or x86 phone could never run them. CI drops an
        // arm64-v8a native library into the APK, which makes Android refuse to
        // install it on any other ABI instead of installing and failing later.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    // The UML kernel will be exec'd straight out of nativeLibraryDir, which is
    // only allowed when the library is extracted rather than used in place.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
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
