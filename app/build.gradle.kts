plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "io.ahmed.sysmon"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.ahmed.sysmon"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
        }
    }

    buildTypes {
        release {
            // R8 shrinks + optimizes; resource shrink requires minify. Safe here
            // because Room/KSP generate at compile time, and we keep rules for
            // OkHttp reflection + MPAndroidChart via proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
                "mozilla/public-suffix-list.txt",
            )
        }
    }
}

dependencies {
    // Kotlin
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Compose
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    // Required by themes.xml's Theme.Material3.DayNight.NoActionBar parent
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.navigation:navigation-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Room
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // OkHttp for router HTTP (LAN)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // WorkManager for daily report
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // EncryptedSharedPreferences — AES-256 via Android Keystore, no network
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // DataStore for small settings (unencrypted)
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // MPAndroidChart for on-device charts
    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")

    // Optional, off by default — Jakarta Mail for Android. Packaged but not wired.
    // Enabling it is a Settings toggle + code path in a future MailSender.kt.
    // implementation("com.sun.mail:android-mail:1.6.7")
    // implementation("com.sun.mail:android-activation:1.6.7")

    // Test
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
