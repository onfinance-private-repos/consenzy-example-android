# Vendored Consenzy Android SDK

Vendored copy of the Consenzy Android SDK (Apache-2.0, Copyright 2026 OnFinance) from version 0.1.0. Temporary until the SDK is published to Maven Central; switch to the registry dependency (`in.onfinance:consenzy-android`, `in.onfinance:consenzy-android-ui`) then.

| Directory | Gradle project in this repo | Maven Central artifact (once published) |
|---|---|---|
| [`core/`](core) | `:consenzy-core` | `in.onfinance:consenzy-android` |
| [`ui/`](ui) | `:consenzy-ui` | `in.onfinance:consenzy-android-ui` |

This directory is licensed under the Apache License, Version 2.0 (see [LICENSE](LICENSE) and [NOTICE](NOTICE)),
not under the MIT licence of the rest of this repository. Only the client SDK is open source; the Consenzy server,
API and operator portal are proprietary OnFinance software.

## Changes from the upstream SDK

- The Kotlin sources (`core/src/main`, `ui/src/main`) and `ui/consumer-rules.pro` are unchanged.
- `core/build.gradle.kts` and `ui/build.gradle.kts` are modified (each says so at the top): the Maven Central
  publishing plugin and the SDK's own test dependencies are removed, and `:ui` depends on `:consenzy-core`.
- The SDK's tests, Gradle wrapper and root build files are not vendored. The plugin and library versions they need
  are in this repository's [`gradle/libs.versions.toml`](../../gradle/libs.versions.toml).

## Switching to Maven Central

1. Delete `vendor/consenzy-android/`.
2. Remove the two `include(...)` / `projectDir` lines from [`settings.gradle.kts`](../../settings.gradle.kts).
3. In [`app/build.gradle.kts`](../../app/build.gradle.kts) replace `implementation(project(":consenzy-ui"))` with:

   ```kotlin
   implementation("in.onfinance:consenzy-android:0.1.0")
   implementation("in.onfinance:consenzy-android-ui:0.1.0")
   ```
