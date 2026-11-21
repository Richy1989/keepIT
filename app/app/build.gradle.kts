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
    // 37 because current AndroidX, Compose and Coil releases refuse to build against anything
    // older (checkAarMetadata). compileSdk only decides which Android APIs the code can see: the
    // devices it runs on are minSdk's business, and platform behaviour follows targetSdk.
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "org.hyperstarit.keepitapp"
        minSdk = 34
        targetSdk = 36
        // Static version is the source of truth — F-Droid reads these literals from the tagged
        // source (its checkupdates can't run Gradle or read env). Bump both when cutting a release
        // so the tag vX.Y.Z matches: the code is X*1000000 + Y*10000 + Z*100 + 99, and release.yml
        // refuses a tag they disagree with. CI still overrides them from the tag via env for GitHub
        // builds, which is all a beta tag vX.Y.Z-beta.N is: it leaves these at the last release,
        // and CI builds it as X.Y.Z-beta.N with code X*1000000 + Y*10000 + Z*100 + N.
        versionCode = 1050399
        versionName = "1.5.3"
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

    // WorkManager: Glance already runs the widget composition in a worker, and WidgetSyncWorker
    // keeps the widget current while the app is closed. The -ktx artifact is the one carrying
    // CoroutineWorker.
    implementation(libs.androidx.work.runtime.ktx)

    // Note images. Coil only decodes and caches in memory — MediaCache already did the
    // authenticated fetch and hands it a File, so there is no custom fetcher to keep alive.
    implementation(libs.coil.compose)

    // Note bodies: CommonMark + the GFM extensions the web's remark-gfm enables, so both clients
    // read a note the same way. Rendering, the editor's live styling and the plain-text previews
    // all come from one parse (ui/markdown/).
    implementation(libs.commonmark)
    implementation(libs.commonmark.ext.autolink)
    implementation(libs.commonmark.ext.gfm.strikethrough)
    implementation(libs.commonmark.ext.gfm.tables)
    implementation(libs.commonmark.ext.task.list.items)

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
/**
 * Copies the repository's `backgrounds/` folder (the note background images, shared with the web
 * app) into the assets folder `backgrounds/`, where `ui/notes/NoteBackgrounds.kt` reads them. Its
 * README stays behind; `NoteBackgroundFilesTest` holds the rest to the rules it states.
 */
abstract class CopyNoteBackgrounds : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val source: DirectoryProperty

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun copy() {
        val target = output.get().asFile.resolve("backgrounds")
        target.deleteRecursively()
        target.mkdirs()
        source.get().asFile.listFiles().orEmpty()
            .filter { it.isFile && it.name != "README.md" }
            .forEach { it.copyTo(target.resolve(it.name)) }
    }
}

val copyNoteBackgrounds = tasks.register<CopyNoteBackgrounds>("copyNoteBackgrounds") {
    source.set(rootProject.layout.projectDirectory.dir("../backgrounds"))
}

/**
 * Turns the same `backgrounds/` folder into drawable resources for the home-screen widget, which
 * can't use the assets: a widget is drawn by the launcher from RemoteViews, and those can only
 * name an image (a resource, a bitmap, a URI), not paint a tiled shader as the app's cards do.
 *
 * Per background `<name>` it writes:
 *  - `drawable-xhdpi/note_bg_tile_<id>.<ext>`: the file itself. xhdpi is 2×, so the system scales
 *    it to half its pixels in dp, the same size as the app and the web draw it (backgrounds/README.md).
 *  - `drawable/note_bg_<id>.xml`: that file tiled from the top left, inside a layer whose intrinsic
 *    size is 1 dp but which fills whatever it is drawn into. The tiny size is the point: a widget
 *    row's height is its text's, and an ImageView reports its drawable's size while being measured,
 *    so a 120 dp tile would stretch every short row to 120 dp.
 *  - `WidgetBackgroundDrawables.kt`: the note's stored name → that drawable. Generated code rather
 *    than `getIdentifier`, so a missing resource fails the compile and the resource shrinker can
 *    see every reference.
 *
 * `<id>` is the name with its hyphens as underscores (resource names can't hold a hyphen, and a
 * background name can't hold an underscore, so no two names meet). A file that is not a valid
 * background is left out here, as the app leaves it out of the picker; `NoteBackgroundFilesTest`
 * fails the build's tests on it anyway.
 */
abstract class NoteBackgroundDrawables : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val source: DirectoryProperty

    @get:OutputDirectory
    abstract val res: DirectoryProperty

    @get:OutputDirectory
    abstract val kotlin: DirectoryProperty

    @TaskAction
    fun generate() {
        val file = Regex("^([a-z0-9]+(?:-[a-z0-9]+)*)\\.(png|webp)$")
        val backgrounds = source.get().asFile.listFiles().orEmpty()
            .filter { it.isFile }
            .mapNotNull { f -> file.matchEntire(f.name)?.let { m -> Triple(m.groupValues[1], m.groupValues[2], f) } }
            .filter { (name) -> name.length <= 64 }
            .sortedBy { (name) -> name }

        val resRoot = res.get().asFile.apply { deleteRecursively() }
        val tiles = resRoot.resolve("drawable-xhdpi").apply { mkdirs() }
        val drawables = resRoot.resolve("drawable").apply { mkdirs() }
        for ((name, ext, f) in backgrounds) {
            val id = name.replace('-', '_')
            f.copyTo(tiles.resolve("note_bg_tile_$id.$ext"))
            drawables.resolve("note_bg_$id.xml").writeText(
                """
                |<?xml version="1.0" encoding="utf-8"?>
                |<!-- Generated by noteBackgroundDrawables (app/build.gradle.kts) from backgrounds/$name.$ext. -->
                |<layer-list xmlns:android="http://schemas.android.com/apk/res/android">
                |    <item android:width="1dp" android:height="1dp" android:gravity="fill">
                |        <bitmap android:src="@drawable/note_bg_tile_$id" android:tileMode="repeat" />
                |    </item>
                |</layer-list>
                |""".trimMargin()
            )
        }

        val pkg = "org.hyperstarit.keepitapp.widget"
        val kt = kotlin.get().asFile.apply { deleteRecursively() }
            .resolve(pkg.replace('.', '/')).apply { mkdirs() }
            .resolve("WidgetBackgroundDrawables.kt")
        val cases = backgrounds.joinToString("\n") { (name) ->
            "        \"$name\" -> R.drawable.note_bg_${name.replace('-', '_')}"
        }
        kt.writeText(
            """
            |// Generated by noteBackgroundDrawables (app/build.gradle.kts) from backgrounds/. Do not edit.
            |package $pkg
            |
            |import org.hyperstarit.keepitapp.R
            |
            |/** The widget's drawable for each note background this build ships, by the name a note stores. */
            |internal object WidgetBackgroundDrawables {
            |    /** The tiled drawable for [name]; null for no background, or one this build doesn't ship. */
            |    fun of(name: String?): Int? = when (name) {
            |$cases
            |        else -> null
            |    }
            |}
            |""".trimMargin()
        )
    }
}

val noteBackgroundDrawables = tasks.register<NoteBackgroundDrawables>("noteBackgroundDrawables") {
    source.set(rootProject.layout.projectDirectory.dir("../backgrounds"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyNoteBackgrounds, CopyNoteBackgrounds::output)
        variant.sources.res?.addGeneratedSourceDirectory(noteBackgroundDrawables, NoteBackgroundDrawables::res)
        variant.sources.kotlin?.addGeneratedSourceDirectory(noteBackgroundDrawables, NoteBackgroundDrawables::kotlin)
    }
}

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
    "org.hyperstarit.keepitapp.widget.WidgetSyncWorker" to
        "our periodic widget refresh - WorkManager reads the class name back out of its own database, so stripping this stops the widget updating in the background with nothing logged",
    "androidx.core.content.FileProvider" to
        "named in AndroidManifest and instantiated by the framework from that string - losing its constructor breaks camera capture in release builds only, with no build-time or logged failure",
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

// Four unit tests read the web app's files to hold this app to it: WebTokenParityTest (the colour
// tokens in index.css), AboutContentParityTest (the About page's about.json), ListIconsParityTest
// (the list icon picker's listIcons.json) and ChecklistMergeTest (checklistMerge.json, the cases of
// the checklist merge). Declared as test inputs so that a change on the web side
// alone reruns them; otherwise Gradle would call the tests up to date, and the drift would only show
// on a clean build such as CI's. AboutCreditsTest reads this script's dependencies the same way, so
// the script is an input too.
tasks.withType<Test>().configureEach {
    inputs.files(
        rootProject.file("../web/src/index.css"),
        rootProject.file("../web/src/features/about/about.json"),
        rootProject.file("../web/src/features/lists/listIcons.json"),
        rootProject.file("../web/src/features/notes/checklistMerge.json"),
    ).withPropertyName("webParitySources").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file("build.gradle.kts").withPropertyName("dependencyList").withPathSensitivity(PathSensitivity.RELATIVE)
}
