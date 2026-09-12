import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // AGP 9 supplies Kotlin itself; applying org.jetbrains.kotlin.android here is an error.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.blackout.app"
    // Current androidx/Compose artifacts require compiling against API 37+.
    // compileSdk is independent of targetSdk, which stays at 36 (the iQOO 15 runs Android 16).
    compileSdk = 37

    defaultConfig {
        applicationId = "com.blackout.app"
        // 26 = adaptive launcher icons, so the icon can be pure vector with no PNG fallbacks.
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            // The iQOO 15 reports an empty abilist32 - it is 64-bit only. Shipping just
            // arm64-v8a keeps the debug APK small so Run/Apply Changes stays fast.
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
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

    // Under built-in Kotlin the `kotlin` block lives inside `android { }`.
    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Compose BOM governs all androidx.compose.* versions below
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    debugImplementation(libs.androidx.ui.tooling)

    // CameraX
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // ML Kit on-device text recognition.
    // Not used in v0 - kept because OCR is the next step and the artifact is already cached.
    implementation(libs.mlkit.text.recognition)

    // On-device LLM cascade (Qwen3 workhorse + Gemma referee)
    implementation(libs.litertlm.android)

    testImplementation(libs.junit)
}
