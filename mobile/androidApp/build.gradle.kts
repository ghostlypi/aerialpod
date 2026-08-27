import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

// Read once, at configuration time. Missing or malformed is a hard failure:
// silently defaulting would ship a build whose version is not the one the
// repository says it is.
val versionProps = Properties().apply {
    val file = rootProject.file("version.properties")
    require(file.exists()) { "version.properties is missing from ${rootProject.projectDir}" }
    file.inputStream().use { load(it) }
}
val appVersionCode = requireNotNull(versionProps.getProperty("versionCode")) {
    "version.properties has no versionCode"
}.trim().toInt()
val appVersionName = requireNotNull(versionProps.getProperty("versionName")) {
    "version.properties has no versionName"
}.trim()

android {
    namespace = "org.aerialpod.android"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        // The identity Google Play knows the app by. Permanent once published,
        // and independent of `namespace` above — which stays org.aerialpod.*
        // so the Kotlin packages, and the ProGuard rules that match them, do
        // not have to move.
        applicationId = "com.parthiyer.aerialpod"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = appVersionCode
        versionName = appVersionName
    }

    buildFeatures {
        compose = true
    }

    // Signing config from a local keystore, absent on machines that do not have
    // one — so a checkout without it still builds debug and simply cannot sign
    // a release, rather than failing to configure at all.
    val keystoreFile = rootProject.file("release.keystore")
    signingConfigs {
        if (keystoreFile.exists()) {
            create("release") {
                storeFile = keystoreFile
                storePassword = System.getenv("AERIALPOD_KEYSTORE_PASSWORD") ?: "aerialpod"
                keyAlias = "aerialpod"
                keyPassword = System.getenv("AERIALPOD_KEY_PASSWORD") ?: "aerialpod"
            }
        }
    }

    buildTypes {
        debug {
            // So a debug build can sit next to a release one on the same phone —
            // which is how the peer mesh gets tested with two devices to hand.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            if (keystoreFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
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

    packaging {
        // This project has no native code. The only `.so` files in the APK come
        // from androidx.graphics:graphics-path, an AAR that ships them already
        // built, and AGP 9 would otherwise run them through the NDK's llvm-strip
        // — making a pure-Kotlin build depend on a 2 GB toolchain to re-strip
        // somebody else's prebuilt binaries. Keep them as shipped.
        jniLibs.keepDebugSymbols += "**/*.so"

        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/versions/9/previous-compilation-data.bin",
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":shared"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)

    // The engine behind the HttpClient the core is handed. The core declares
    // only ktor-client-core; choosing the engine is the app's job.
    implementation(libs.ktor.client.okhttp)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.androidx.work)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    debugImplementation(libs.compose.ui.tooling)

    // Not `kotlin("test")`: that helper came from the Kotlin Android plugin,
    // which AGP 9's built-in Kotlin support replaced. The -junit variant is
    // the one that brings a runner with it.
    testImplementation(libs.kotlin.test.junit)
}
