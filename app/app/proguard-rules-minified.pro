# Extra R8 rules for the test-only `minified` variant, applied on top of proguard-rules.pro.
#
# `minified` is release's R8 configuration on an APK that can actually be instrumented. Every rule
# below is a deliberate deviation from what ships, so the list is kept to one principle:
#
#     keep the surface the tests LINK against; leave everything they test to be shrunk.
#
# That line matters. The bugs these tests exist to catch live in library internals that are built
# reflectively — androidx.work's input mergers, Glance's session worker, Room's generated database.
# None of those are named below, so they are shrunk here exactly as they are in release, and a
# missing keep rule still reproduces as a red test. What is named below is only the API surface the
# test APK holds a compile-time reference to.
#
# Why any of this is needed: instrumentation loads the test APK and the app APK into one process,
# but R8 minifies them in two passes. The test pass sees the app's *pre-minified* classes as
# library input, so it assumes they will exist at runtime and declines to package them — while the
# app's own pass has already renamed, merged or dropped them. The result is a test APK linked
# against names that no longer exist:
#
#     NoClassDefFoundError: Failed resolution of: Landroidx/tracing/Trace;   (the runner itself)
#     NoClassDefFoundError: Failed resolution of: Lorg/hyperstarit/keepitapp/widget/KeepItWidget;
#
# KeepItWidget is the instructive one: nothing stripped it, R8 *merged* it into its only caller.
# Optimization, not shrinking — and exactly the kind of thing a test must not depend on.

# --- 1. names, not shapes ----------------------------------------------------------------------
# The test APK references app classes by name. Renaming them in one pass and not the other breaks
# every test for reasons that have nothing to do with the app. Shrinking still runs in full, and
# shrinking — not renaming — is what strips the reflectively-built constructors we care about.
# AndroidX's benchmark plugin does the same thing for the same reason.
-dontobfuscate

# --- 2. what the instrumentation runner itself needs --------------------------------------------
# Libraries the runner pulls in that app code happens never to touch, so the app's pass drops them.
-keep class androidx.tracing.** { *; }
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class androidx.lifecycle.** { *; }

# --- 3. the app entry points the tests link against ---------------------------------------------
# Deliberately specific rather than `org.hyperstarit.keepitapp.**`: everything else of ours stays
# shrinkable, so a test can't quietly come to depend on an internal that release doesn't ship.
-keep class org.hyperstarit.keepitapp.MainActivity { *; }
-keep class org.hyperstarit.keepitapp.widget.KeepItWidget { *; }
-keep class org.hyperstarit.keepitapp.data.NotesRepository { *; }
-keep class org.hyperstarit.keepitapp.data.NotesRepository$Companion { *; }
-keep class org.hyperstarit.keepitapp.data.WidgetNote { *; }

# --- 4. the library API the tests call -----------------------------------------------------------
# WorkManager's *public* surface only. androidx.work.impl.** and the input mergers are pointedly
# absent: that is where the bug lives, so it stays shrunk and stays reproducible here.
-keep class androidx.work.WorkManager { *; }
-keep class androidx.work.WorkRequest { *; }
-keep class androidx.work.WorkRequest$Builder { *; }
-keep class androidx.work.OneTimeWorkRequest { *; }
-keep class androidx.work.OneTimeWorkRequest$Builder { *; }
-keep class androidx.work.WorkInfo { *; }
-keep class androidx.work.WorkInfo$State { *; }
-keep class androidx.work.Operation { *; }
-keep class androidx.work.Data { *; }

# Glance's composition entry point, used by WidgetCompositionSmokeTest to render the widget without
# a launcher. The session/worker machinery behind updateAll() is untouched.
-keep class androidx.glance.appwidget.GlanceAppWidget { *; }
-keep class androidx.glance.appwidget.AppWidgetComposerKt { *; }
