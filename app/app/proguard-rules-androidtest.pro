# R8 rules for the **instrumented-test APK only** (testBuildType = "minified").
#
# These are wired through `testProguardFiles`, so nothing here can loosen what ships: the app APK
# is still built from proguard-rules.pro alone. That separation is the point — the test APK needs
# keeps the app must never have (test classes are reachable only from the runner, reflectively),
# and the app's rules must stay honest about what production actually needs.

# --- the test classes themselves ---------------------------------------------------------------
# JUnit4 finds test classes and methods by reflection off the runner's class list, so R8 sees the
# whole package as dead code. Without this the suite "passes" by running zero tests.
-keep class org.hyperstarit.keepitapp.smoke.** { *; }

# SmokeWorker is worse than dead-looking: WorkManager builds it from a class *name* string, so even
# the runner never references it directly.
-keep class * extends androidx.work.ListenableWorker { *; }

# --- test-only transitive noise ----------------------------------------------------------------
# Guava (via WorkManager's ListenableFuture) and the AndroidX test libraries carry build-time
# annotations that reference JDK-only classes. They're never loaded on a device; R8 only complains
# because it can't find them on the classpath.
-dontwarn javax.lang.model.**
-dontwarn javax.annotation.**
-dontwarn com.google.errorprone.**
-dontwarn com.google.j2objc.**
-dontwarn org.checkerframework.**
-dontwarn sun.misc.**
