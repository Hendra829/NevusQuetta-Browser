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
        versionCode = 910
        versionName = "0.9.0-cd-rc1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
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
    val releaseStorePath =
        providers.environmentVariable("NEVUS_RELEASE_STORE").orNull?.trim().orEmpty()
    val releaseStorePassword =
        providers.environmentVariable("NEVUS_RELEASE_STORE_PASSWORD").orNull?.trim().orEmpty()
    val releaseAlias =
        providers.environmentVariable("NEVUS_RELEASE_ALIAS").orNull?.trim().orEmpty()
    val releaseKeyPassword =
        providers.environmentVariable("NEVUS_RELEASE_KEY_PASSWORD").orNull?.trim().orEmpty()
    val releaseStoreFile = releaseStorePath
        .takeIf(String::isNotBlank)
        ?.let(::file)
    val hasReleaseSigning =
        releaseStoreFile?.isFile == true &&
            releaseStorePassword.isNotBlank() &&
            releaseAlias.isNotBlank() &&
            releaseKeyPassword.isNotBlank()
    val releaseRequested = gradle.startParameter.taskNames.any {
        it.contains("release", ignoreCase = true)
    }

    if (releaseRequested && !hasReleaseSigning) {
        throw GradleException(
            "Release signing is fail-closed: set NEVUS_RELEASE_STORE, " +
                "NEVUS_RELEASE_STORE_PASSWORD, NEVUS_RELEASE_ALIAS, and " +
                "NEVUS_RELEASE_KEY_PASSWORD to a valid release keystore.",
        )
    }

    val releaseSigning = if (hasReleaseSigning) {
        signingConfigs.create("release") {
            storeFile = releaseStoreFile
            storePassword = releaseStorePassword
            keyAlias = releaseAlias
            keyPassword = releaseKeyPassword
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = true
        }
    } else {
        null
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = releaseSigning
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            ndk {
                debugSymbolLevel = "SYMBOL_TABLE"
            }
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
        animationsDisabled = true
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
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    kapt("androidx.room:room-compiler:2.7.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("androidx.work:work-runtime-ktx:2.10.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.room:room-testing:2.7.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.robolectric:robolectric:4.14.1")

    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit-ktx:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
