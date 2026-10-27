// Copyright 2026 OnFinance. SPDX-License-Identifier: Apache-2.0
//
// MODIFIED for consenzy-example-android: Maven Central publishing (com.vanniktech.maven.publish)
// and the SDK's own test dependencies were removed, and the core module is referenced as
// project(":consenzy-core"), because this vendored copy is only built as a source dependency of
// the example app (Gradle project ":consenzy-ui"). The sources are unchanged.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "in.onfinance.consent.ui"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        compose = true
        buildConfig = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
    }
}

dependencies {
    api(project(":consenzy-core"))
    api(platform(libs.compose.bom))
    api(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    api(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.coroutines.android)
}
