plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
}

android {
    namespace = "com.nevus.quetta"
    compileSdk = 36
    ndkVersion = "27.2.12479018"
    defaultConfig {
        applicationId = "com.nevus.quetta"
        minSdk = 26
        targetSdk = 36
        versionCode = 900
        versionName = "0.9.0-ab"
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-O2", "-fno-exceptions")
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }
    buildFeatures { viewBinding = true; buildConfig = true }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    val releaseStore = providers.environmentVariable("NEVUS_RELEASE_STORE")
    val releaseStorePassword = providers.environmentVariable("NEVUS_RELEASE_STORE_PASSWORD")
    val releaseAlias = providers.environmentVariable("NEVUS_RELEASE_ALIAS")
    val releaseKeyPassword = providers.environmentVariable("NEVUS_RELEASE_KEY_PASSWORD")
    val hasReleaseSigning = listOf(
        releaseStore,
        releaseStorePassword,
        releaseAlias,
        releaseKeyPassword,
    ).all { it.isPresent && it.get().isNotBlank() }

    val releaseSigning = if (hasReleaseSigning) {
        signingConfigs.create("release") {
            storeFile = file(releaseStore.get())
            storePassword = releaseStorePassword.get()
            keyAlias = releaseAlias.get()
            keyPassword = releaseKeyPassword.get()
        }
    } else {
        null
    }
    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            signingConfig = releaseSigning
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        jvmToolchain(17)
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    packaging { jniLibs { useLegacyPackaging = false } }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kapt {
    correctErrorTypes = true
    arguments {
        arg("room.schemaLocation", file("schemas").absolutePath)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.10.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    kapt("androidx.room:room-compiler:2.7.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.webkit:webkit:1.14.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.room:room-testing:2.7.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
