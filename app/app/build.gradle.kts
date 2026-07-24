import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release signing credentials come from app/keystore.properties (local, gitignored) or, in CI, from
// env vars. Same signingConfig works both ways: props file wins, env is the fallback. Absent both,
// release builds are simply left unsigned (debug builds are never affected).
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) FileInputStream(f).use { load(it) }
}
fun cred(key: String, env: String): String? =
    keystoreProps.getProperty(key) ?: System.getenv(env)

val hasReleaseSigning = cred("storeFile", "KEYSTORE_PATH") != null

android {
    namespace = "org.hyperstarit.keepitapp"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "org.hyperstarit.keepitapp"
        minSdk = 34
        targetSdk = 36
        // Static version is the source of truth — F-Droid reads these literals from the tagged
        // source (its checkupdates can't run Gradle or read env). Bump both when cutting a release
        // so the tag vX.Y.Z matches. CI still overrides them from the tag via env for GitHub builds.
        versionCode = 507
        versionName = "0.5.7"
        System.getenv("VERSION_CODE")?.toIntOrNull()?.let { versionCode = it }
        System.getenv("VERSION_NAME")?.let { versionName = it }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = file(cred("storeFile", "KEYSTORE_PATH")!!)
                storePassword = cred("storePassword", "KEYSTORE_PASSWORD")
                keyAlias = cred("keyAlias", "KEY_ALIAS")
                keyPassword = cred("keyPassword", "KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Only sign when creds are available (CI or a local keystore.properties); otherwise the
            // release APK is left unsigned rather than failing the build.
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.navigation.compose)

    // Networking: Retrofit + OkHttp with kotlinx.serialization for the keepIT REST API.
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    // Realtime: the official SignalR Java client against the API's RealTimeHub.
    implementation(libs.microsoft.signalr)

    // Home-screen widget (Glance).
    implementation(libs.androidx.glance.appwidget)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
