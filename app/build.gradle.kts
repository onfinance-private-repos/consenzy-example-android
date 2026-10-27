plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// "Session server" mode calls this URL for POST /session and POST /consent-check.
// Set it with -PconsenzySessionServerUrl=… or in gradle.properties. Never put an org API key (cdm_…) here.
val sessionServerUrl: String = providers.gradleProperty("consenzySessionServerUrl").getOrElse("http://10.0.2.2:8787")

android {
    namespace = "com.example.consenzy"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.consenzy"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        buildConfigField("String", "SESSION_SERVER_URL", "\"$sessionServerUrl\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Example only: the release build is signed with the debug key so `assembleRelease` works anywhere.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            all { it.testLogging { events("passed", "skipped", "failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL } }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Consenzy Android SDK: core (in.onfinance:consenzy-android) + Compose UI (in.onfinance:consenzy-android-ui),
    // vendored under vendor/consenzy-android until they are on Maven Central.
    implementation(project(":consenzy-ui"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
}
