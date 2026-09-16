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
        versionCode = 600
        versionName = "0.6.0"
        System.getenv("VERSION_CODE")?.toIntOrNull()?.let { versionCode = it }
        System.getenv("VERSION_NAME")?.let { versionName = it }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Don't embed AGP's "Dependency metadata" signing block: it's an opaque, Play-oriented
    // dependency blob that F-Droid's APK scanner rejects as an extra signing block.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
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
            // R8: tree-shake + minify. Without it the release APK ships every Material icon
            // (~10k unused classes) and all of Compose/SignalR/Retrofit unshrunk — ~50 MB of dex.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }

        // Release's shrinking, debug's signing. Instrumented tests need an APK they can install
        // and instrument anywhere - the release key lives in CI secrets and only exists on a tag,
        // so `release` itself is untestable on a PR. This variant closes that gap: identical R8
        // config (the whole point - it's what makes stripped-by-R8 bugs reproducible), debug key so
        // it installs on any device, and not debuggable so it behaves like what users get.
        create("minified") {
            initWith(getByName("release"))
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            applicationIdSuffix = ".minified"
            // Test-APK-only rules. Kept separate so a keep needed by the runner can never
            // silently paper over something production genuinely needs.
            // initWith() already brought release's proguardFiles across; this one adds the
            // handful of keeps the instrumentation runner needs. See the file for why.
            proguardFile("proguard-rules-minified.pro")
            testProguardFiles("proguard-rules-androidtest.pro")
        }
    }

    // Point `connectedAndroidTest` at the minified variant: an instrumented test that runs against
    // an unminified debug build cannot see the failure mode we care about.
    testBuildType = "minified"
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
    // Transitive at runtime (via Glance); named here so the smoke tests can enqueue a worker.
    androidTestImplementation(libs.androidx.work.runtime)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// ---------------------------------------------------------------------------------------------
// Release-only guard: R8 must not strip a constructor something instantiates reflectively.
//
// This is the bug class that release builds keep hitting and no other test can see. R8 resolves
// reachability statically, so a class only ever built by `Class.forName(name).newInstance()` looks
// dead: it keeps the class (a `-keep class *` rule saves the name) and quietly drops the
// constructor. Nothing crashes at build time and nothing crashes at startup — the reflective call
// throws deep inside a library, which swallows it, and a feature simply never runs. The widget
// wedged on its loading layout for two releases this way (androidx.work.OverwritingInputMerger).
//
// So: after R8 runs, read its own reports and assert each entry below still has its constructor.
// usage.txt lists what was removed (a bare `com.Foo` line = whole class, `com.Foo:` + indented
// members = those members), mapping.txt lists what survived. Cheap, deterministic, no emulator.
val reflectivelyConstructed = mapOf(
    "androidx.work.OverwritingInputMerger" to
        "the default input merger for every OneTimeWorkRequest - WorkerWrapper builds it before running ANY worker, so losing it fails every job in the process, Glance's widget session included",
    "androidx.work.ArrayCreatingInputMerger" to
        "the other built-in merger, reached by the same reflective path",
    "androidx.glance.session.SessionWorker" to
        "the CoroutineWorker Glance composes the widget inside; WorkManager instantiates it from a class name it persisted",
    "androidx.work.impl.WorkDatabase_Impl" to
        "Room-generated and only ever built by Room.databaseBuilder - WorkManager's startup initializer crashes the app on cold start without it",
    "org.hyperstarit.keepitapp.widget.RefreshAction" to
        "our Glance ActionCallback, resolved by Class.forName from the PendingIntent when the user taps refresh",
)

val verifyReleaseKeepRules = tasks.register("verifyReleaseKeepRules") {
    group = "verification"
    description = "Asserts R8 kept the constructors of everything we build reflectively."
    dependsOn("minifyReleaseWithR8")

    val mappingDir = layout.buildDirectory.dir("outputs/mapping/release")
    val usageFile = mappingDir.map { it.file("usage.txt") }
    val mappingFile = mappingDir.map { it.file("mapping.txt") }
    val guarded = reflectivelyConstructed
    inputs.files(usageFile, mappingFile)

    doLast {
        val usage = usageFile.get().asFile
        val mapping = mappingFile.get().asFile
        check(usage.isFile && mapping.isFile) {
            "R8 reports not found in ${mappingDir.get().asFile} - did minifyReleaseWithR8 run?"
        }

        // usage.txt: gather the members R8 removed, per class.
        val removedMembers = mutableMapOf<String, MutableList<String>>()
        val removedClasses = mutableSetOf<String>()
        var current: String? = null
        usage.forEachLine { line ->
            when {
                line.startsWith(" ") || line.startsWith("	") ->
                    current?.let { removedMembers.getOrPut(it) { mutableListOf() } += line.trim() }
                line.endsWith(":") -> current = line.dropLast(1)
                line.isNotBlank() -> { removedClasses += line; current = null }
                else -> current = null
            }
        }
        val survived = mapping.useLines { lines ->
            lines.filter { it.isNotEmpty() && !it.startsWith(" ") && !it.startsWith("#") && " -> " in it }
                .map { it.substringBefore(" -> ") }
                .toSet()
        }

        val failures = guarded.mapNotNull { (fqcn, why) ->
            val lostConstructor = removedMembers[fqcn].orEmpty().any { it.contains("<init>") }
            when {
                fqcn in removedClasses -> "$fqcn - class removed entirely"
                fqcn !in survived -> "$fqcn - not in mapping.txt (renamed away or never reached R8)"
                lostConstructor -> "$fqcn - class kept but its constructor was stripped"
                else -> null
            }?.let { problem -> "  * $problem\n      needed because: $why" }
        }

        if (failures.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("R8 stripped ${failures.size} reflectively-constructed member(s):")
                    failures.forEach { appendLine(it) }
                    appendLine()
                    appendLine("Add or widen a -keep rule in app/proguard-rules.pro. A bare")
                    appendLine("`-keep class Foo` keeps only the NAME - the constructor needs an explicit")
                    appendLine("member rule: `-keep class Foo { <init>(); }`.")
                    append("Evidence: ${usage.absolutePath}")
                }
            )
        }
        logger.lifecycle("verifyReleaseKeepRules: ${guarded.size} reflectively-constructed types intact.")
    }
}

// Guard every path that produces a release build, local `assembleRelease` included - a keep-rule
// regression must never reach an APK, whether it was built here or by the release workflow.
tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }.configureEach {
    finalizedBy(verifyReleaseKeepRules)
}
