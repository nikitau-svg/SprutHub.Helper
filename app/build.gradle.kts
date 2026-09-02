plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val signingStoreFilePath = System.getenv("ANDROID_SIGNING_STORE_FILE").orEmpty()
val signingStorePassword = System.getenv("ANDROID_SIGNING_STORE_PASSWORD").orEmpty()
val signingKeyAlias = System.getenv("ANDROID_SIGNING_KEY_ALIAS").orEmpty()
val signingKeyPassword = System.getenv("ANDROID_SIGNING_KEY_PASSWORD").orEmpty()
val stableSigningAvailable = listOf(
    signingStoreFilePath,
    signingStorePassword,
    signingKeyAlias,
    signingKeyPassword,
).all(String::isNotBlank) && file(signingStoreFilePath).isFile

android {
    namespace = "io.github.nikitau.spruthubhelper"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.nikitau.spruthubhelper"
        minSdk = 30
        targetSdk = 35
        versionCode = 39
        versionName = "0.8.0-beta.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        if (stableSigningAvailable) {
            create("stable") {
                storeFile = file(signingStoreFilePath)
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
                storeType = "PKCS12"
            }
        }
    }

    buildTypes {
        debug {
            if (stableSigningAvailable) signingConfig = signingConfigs.getByName("stable")
        }
        create("screenshot") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".screenshots"
            versionNameSuffix = "-screenshots"
            isDebuggable = true
            matchingFallbacks += listOf("debug")
        }
        release {
            if (stableSigningAvailable) signingConfig = signingConfigs.getByName("stable")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCIES",
            "/META-INF/versions/9/OSGI-INF/MANIFEST.MF",
        )
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.2")
    implementation("androidx.work:work-runtime:2.11.2")
    implementation("androidx.health.connect:connect-client:1.1.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.google.android.material:material:1.12.0")
    // 21.4.0 is compiled with Kotlin 2.3 metadata; this project remains on the
    // AGP 8.9 / Kotlin 2.0 compatibility line for Android 11 support.
    implementation("com.google.android.gms:play-services-location:21.3.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk9:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.85.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
