plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nevus.quetta"
    compileSdk = 35
    ndkVersion = "27.2.12479018"
    defaultConfig {
        applicationId = "com.nevus.quetta"
        minSdk = 26
        targetSdk = 35
        versionCode = 122
        versionName = "1.2.2"
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
    signingConfigs {
        create("lab") {
            storeFile = rootProject.file("keystore/lab.jks")
            storePassword = "nevuslab122"
            keyAlias = "nevuslab"
            keyPassword = "nevuslab122"
        }
    }
    buildTypes {
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("lab")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("lab")
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.10.0")
    implementation("com.google.android.material:material:1.12.0")
    testImplementation("junit:junit:4.13.2")
}
