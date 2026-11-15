import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Match the Java toolchain's target to Kotlin's (JDK 21 is installed; we emit 17 bytecode).
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(project(":core"))
    // Desktop server engine - Netty supports HTTPS (Ktor CIO does not).
    implementation(libs.ktor.server.netty)
    runtimeOnly(libs.slf4j.simple)
}

// Ktor's Netty engine (Netty 4.2) pulls in HTTP/3 QUIC native libraries for every desktop OS.
// servd doesn't use HTTP/3, so leave them out of the bundle; the QUIC Java classes stay, so the
// engine still links.
configurations.configureEach { exclude(group = "io.netty", module = "netty-codec-native-quic") }

application {
    applicationName = "servd"
    mainClass.set("dev.servd.host.MainKt")
}

// ---------------------------------------------------------------------------
// Packaging: bundle servd with its own JRE via jpackage, so an end
// user needs nothing else installed. `jpackageImage` builds a self-contained
// app folder (no external tooling required); `jpackageInstaller` builds a
// native installer for the current OS (needs WiX on Windows, and produces
// .dmg/.pkg on macOS, .deb/.rpm on Linux).
// ---------------------------------------------------------------------------

val appVersion = providers.gradleProperty("servdVersion").getOrElse("1.0.0")

// jpackage ships inside the JDK; resolve it from the JDK running this build.
fun jpackageExecutable(): File {
    val javaHome = File(System.getProperty("java.home"))
    val exe = if (org.gradle.internal.os.OperatingSystem.current().isWindows) "jpackage.exe" else "jpackage"
    val file = File(javaHome, "bin/$exe")
    check(file.exists()) {
        "jpackage not found at $file - run the build with a full JDK (17+), not a JRE."
    }
    return file
}

// Installer type for the current OS. app-image is a plain self-contained folder.
fun defaultInstallerType(): String = when {
    org.gradle.internal.os.OperatingSystem.current().isWindows -> "msi"   // needs WiX on PATH
    org.gradle.internal.os.OperatingSystem.current().isMacOsX -> "dmg"
    else -> "deb"
}

fun jpackageCommand(type: String): List<String> {
    val installDir = layout.buildDirectory.dir("install/servd").get().asFile
    val libDir = File(installDir, "lib")
    val mainJar = tasks.jar.get().archiveFile.get().asFile.name
    val dest = layout.buildDirectory.dir("jpackage").get().asFile
    val cmd = mutableListOf(
        jpackageExecutable().absolutePath,
        "--type", type,
        "--name", "servd",
        "--app-version", appVersion,
        "--input", libDir.absolutePath,
        "--main-jar", mainJar,
        "--main-class", "dev.servd.host.MainKt",
        "--dest", dest.absolutePath,
        "--vendor", "servd",
        "--description", "servd - your device, as a local-network hub",
    )
    // servd is a console app: it prints the URL/fingerprint and waits for Ctrl+C. Without this,
    // jpackage's Windows launcher is a windowless GUI app - the user would see nothing and have
    // no way to stop it but Task Manager. --win-console gives it a console to read and Ctrl+C.
    if (org.gradle.internal.os.OperatingSystem.current().isWindows) cmd += "--win-console"
    return cmd
}

val jpackageImage by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Build a self-contained servd app image (bundled JRE) via jpackage."
    dependsOn(tasks.named("installDist"))
    doFirst {
        delete(File(layout.buildDirectory.dir("jpackage").get().asFile, "servd"))
        commandLine(jpackageCommand("app-image"))
    }
    doLast {
        println("app image: ${layout.buildDirectory.dir("jpackage/servd").get().asFile}")
    }
}

val jpackageInstaller by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Build a native servd installer for the current OS via jpackage."
    dependsOn(tasks.named("installDist"))
    doFirst { commandLine(jpackageCommand(defaultInstallerType())) }
    doLast {
        println("installer written to: ${layout.buildDirectory.dir("jpackage").get().asFile}")
    }
}
