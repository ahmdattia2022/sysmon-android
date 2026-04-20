# Keep Room entities/DAOs
-keep class io.ahmed.sysmon.data.entity.** { *; }
-keep class io.ahmed.sysmon.data.dao.** { *; }

# OkHttp / Okio — reflection-based platform picks
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# MPAndroidChart reads its classes reflectively in some paths
-keep class com.github.mikephil.charting.** { *; }
-dontwarn com.github.mikephil.charting.**

# Jetpack Security / EncryptedSharedPreferences — Tink reflection
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# Keep Kotlin coroutines debug metadata names out of logs
-dontwarn kotlinx.coroutines.debug.**
