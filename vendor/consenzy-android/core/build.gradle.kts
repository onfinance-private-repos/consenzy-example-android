// Copyright 2026 OnFinance. SPDX-License-Identifier: Apache-2.0
//
// MODIFIED for consenzy-example-android: Maven Central publishing (com.vanniktech.maven.publish)
// and the SDK's own test dependencies were removed, because this vendored copy is only built as a
// source dependency of the example app (Gradle project ":consenzy-core"). The sources are unchanged.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `java-library`
}

kotlin {
    explicitApi()
    compilerOptions {
        // Android consumers run on Java 8+ bytecode.
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

dependencies {
    api(libs.okhttp)
    api(libs.coroutines.core)
    api(libs.serialization.json)
}
