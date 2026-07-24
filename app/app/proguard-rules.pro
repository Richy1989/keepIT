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
