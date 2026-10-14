import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }

        // Plain-JVM code (e.g. java.net enumeration, the Ktor server) shared by BOTH
        // JVM targets - Android and desktop. This is the "jvmShared" intermediate.
        val jvmSharedMain by creating {
            dependsOn(commonMain)
            dependencies {
                // Engine-agnostic Ktor server (routing, TLS material). The concrete engine
                // (Netty on desktop, TBD on Android) is provided by each platform module.
                implementation(libs.ktor.server.core)
                implementation(libs.ktor.server.websockets)
                // Range requests, so shared videos can seek and stream in the chat preview.
                implementation(libs.ktor.server.partial.content)
                implementation(libs.ktor.network.tls.certificates)
                // Ktor client (CIO engine) - outbound HTTP + WebSocket forwarding for the
                // reverse proxy that re-serves a host-run web app to the LAN.
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.cio)
                implementation(libs.ktor.client.websockets)
                implementation(libs.zxing.core)
                // SSH/SFTP (Apache MINA SSHD) and FTP/FTPS (Apache FtpServer) - both host on
                // desktop AND Android (a phone is a full hub), so they live in the shared set.
                implementation(libs.sshd.core)
                implementation(libs.sshd.sftp)
                implementation(libs.ftpserver.core)
                // mDNS: answers for the hub's .local name on both platforms; desktop also
                // advertises/discovers the _servd._tcp service with it (Android uses NsdManager).
                implementation(libs.jmdns)
            }
        }
        val androidMain by getting {
            dependsOn(jvmSharedMain)
        }
        val desktopMain by getting {
            dependsOn(jvmSharedMain)
        }
    }
}

android {
    namespace = "dev.servd.core"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // The dashboard resource lives in the shared jvmSharedMain source set; the Android target
    // does not pick it up automatically, so package it as an Android Java resource too. One copy,
    // reachable via the classloader on both desktop and Android.
    sourceSets["main"].resources.srcDir("src/jvmSharedMain/resources")
}
