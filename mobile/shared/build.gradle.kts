import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.konan.target.HostManager

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    // Since AGP 9 the plain `com.android.library` plugin refuses to sit next to
    // Kotlin Multiplatform. This is its replacement; the Android configuration
    // moved from a top-level `android { }` block to `kotlin { android { } }`.
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.sqldelight)
}

sqldelight {
    databases {
        create("AerialPodDatabase") {
            packageName.set("org.aerialpod.core.db")
            // The desktop schema uses UPSERT throughout (repo.py), which needs
            // SQLite 3.24+. Pinning the dialect here makes a query that would
            // fail on the device fail at compile time instead.
            dialect("app.cash.sqldelight:sqlite-3-25-dialect:${libs.versions.sqldelight.get()}")
        }
    }
}

kotlin {
    android {
        namespace = "org.aerialpod.core"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = libs.versions.minSdk.get().toInt()

        compilations.configureEach {
            compileTaskProvider.configure {
                compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
            }
        }
    }

    // A plain JVM target alongside Android: the shared core is pure Kotlin, so
    // testing it here runs in seconds instead of going through AGP's unit-test
    // pipeline — and it is where the integration harness drives the real
    // desktop peer from.
    jvm()

    // Kotlin/Native cannot cross-compile Apple targets from Linux, so they are
    // declared only on a Mac host. iosMain still lives in the tree.
    if (HostManager.hostIsMac) {
        iosX64()
        iosArm64()
        iosSimulatorArm64()
    }

    applyDefaultHierarchyTemplate {
        common {
            group("jvmShared") {
                // Matched on platform type rather than by `withAndroidTarget()`,
                // which only recognises the old `androidTarget()`. The Android
                // KMP library target reports `androidJvm` just the same.
                withCompilations {
                    it.target.platformType == org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.jvm ||
                        it.target.platformType == org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.androidJvm
                }
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            // `api` for the three that appear in the core's own signatures:
            // AerialPodDatabase is a Transacter, LanPeerService hands out
            // StateFlows, and AerialPodCore is constructed with an HttpClient.
            // With `implementation` the app can call none of it.
            api(libs.sqldelight.runtime)
            api(libs.kotlinx.coroutines.core)
            api(libs.ktor.client.core)
            implementation(libs.sqldelight.coroutines)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.ktor.network)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        androidMain.dependencies {
            implementation(libs.sqldelight.android)
            implementation(libs.ktor.client.okhttp)
        }
        jvmTest.dependencies {
            // The diagnostic harness opens an existing database file, which
            // means building the driver itself rather than going through
            // JvmDriverFactory (that one always creates the schema).
            implementation(libs.sqldelight.jvm)
        }
        jvmMain.dependencies {
            implementation(libs.sqldelight.jvm)
            implementation(libs.ktor.client.okhttp)
        }
    }
}
