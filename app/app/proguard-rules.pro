# R8 keep rules for the keepIT Android release build.
#
# Retrofit, OkHttp and kotlinx.serialization all ship their own consumer rules inside their
# artifacts, so R8 already applies those. What remains is (a) our own @Serializable DTOs — R8
# must not rename/strip the generated $$serializer classes reflection resolves at runtime — and
# (b) the Microsoft SignalR client, which leans on reflection + a bundled Gson and has no
# consumer rules of its own.

# --- kotlinx.serialization: keep our DTO serializers -------------------------------------------
# The generated companion + $$serializer for every @Serializable type in our package. Retrofit's
# kotlinx converter and Json.decodeFromString resolve these reflectively by name.
-keepattributes *Annotation*, InnerClasses
-if @kotlinx.serialization.Serializable class org.hyperstarit.keepitapp.**
-keepclassmembers class org.hyperstarit.keepitapp.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class org.hyperstarit.keepitapp.**$$serializer { *; }

# --- Microsoft SignalR Java client -------------------------------------------------------------
# Reflection-driven hub invocation + its shaded Gson dependency. No consumer rules ship with it.
-keep class com.microsoft.signalr.** { *; }
-dontwarn com.microsoft.signalr.**
-keep class com.google.gson.** { *; }
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-dontwarn com.google.gson.**

# RxJava (SignalR's async surface) and the Nullable annotations it references.
-dontwarn io.reactivex.rxjava3.**
-dontwarn javax.annotation.**

# SignalR logs via slf4j; the optional StaticLoggerBinder is provided at runtime (or absent).
-dontwarn org.slf4j.**

# --- Room-generated database implementations ---------------------------------------------------
# WorkManager (pulled in transitively by androidx.glance:glance-appwidget) is a Room database. Room
# generates a WorkDatabase_Impl whose constructor is only ever invoked reflectively via
# Room.databaseBuilder, so R8 sees it as unreachable and strips it. WorkManager registers itself as
# an androidx.startup initializer under InitializationProvider, which runs at process creation —
# before any activity — so the missing constructor crashes the app on cold start, unrecoverably
# (RuntimeException: Failed to create an instance of androidx.work.impl.WorkDatabase). We don't use
# Room directly; this keeps the generated constructors for every RoomDatabase subclass regardless.
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# --- WorkManager workers: Glance renders the widget inside one ----------------------------------
# `GlanceAppWidget.provideGlance` does not run on the broadcast thread — Glance schedules it as an
# androidx.work.CoroutineWorker (androidx.glance.session.SessionWorker). WorkManager instantiates a
# worker reflectively from a class name it persisted in its own database, so R8 stripping that
# constructor doesn't crash anything: the worker simply never runs, the composition is never
# produced, and the widget sits on its initialLayout (glance_default_loading_layout) forever — a
# white box with a spinner. The name must survive too, not just the constructor: the persisted name
# is read back by a *later* build, so an obfuscated name that shifts between releases breaks work
# that was already queued.
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# --- Glance ActionCallbacks: resolved reflectively at click time --------------------------------
# `actionRunCallback<RefreshAction>()` stores the callback's class *name* in the PendingIntent, and
# the receiver resolves it with Class.forName + a no-arg constructor when the user taps. Same
# cross-build hazard as the workers above: the PendingIntent outlives an app update, so the name has
# to be stable — hence -keep rather than -keepclassmembers.
-keep class * implements androidx.glance.appwidget.action.ActionCallback {
    public <init>();
}

# Defensive: Glance encodes its RemoteViews layout tree with a shaded protobuf-lite, which resolves
# generated message classes reflectively. Glance ships consumer rules for this, but the WorkManager
# breakage above shows the transitive ones don't reliably reach us intact.
-keep class androidx.glance.appwidget.protobuf.** { *; }
