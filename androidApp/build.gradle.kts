import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Release signing (Phase 9): read credentials from an untracked keystore.properties so no secret
// ever lands in the repo (see README for how to generate one). Without it, assembleRelease still
// builds, but produces an unsigned APK you must sign yourself.
val keystorePropsFile = rootProject.file("androidApp/keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

// Version comes from the single source of truth (gradle.properties: servdVersion). versionCode is
// derived from the semver so it always increases: 1.2.3 -> 10203.
val servdVersion = providers.gradleProperty("servdVersion").getOrElse("1.0.0")
val servdVersionCode = servdVersion.split(".").let { p ->
    (p.getOrNull(0)?.toIntOrNull() ?: 0) * 10000 +
        (p.getOrNull(1)?.toIntOrNull() ?: 0) * 100 +
        (p.getOrNull(2)?.toIntOrNull() ?: 0)
}

android {
    namespace = "dev.servd.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "dev.servd.android"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = servdVersionCode
        versionName = servdVersion
        multiDexEnabled = true // Netty/Ktor push past the 64k method limit
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Sign the release only if keystore.properties supplied a config.
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
                "META-INF/*.kotlin_module",
                "META-INF/DEPENDENCIES",
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    // The HTTPS engine for the shared server (Ktor CIO cannot serve HTTPS). SSH/FTP servers come
    // transitively from :core, which now hosts them for both desktop and Android.
    implementation(libs.ktor.server.netty)
    // Rasterize the join QR for the control screen (core's Qr emits SVG).
    implementation(libs.zxing.core)
}

// Ktor's Netty engine (Netty 4.2) pulls in HTTP/3 QUIC native libraries for desktop OSes. servd
// doesn't use HTTP/3, and on Android they're dead weight (their bundled licence files even clash
// at packaging). The QUIC Java classes stay, so the engine still links.
configurations.configureEach { exclude(group = "io.netty", module = "netty-codec-native-quic") }
