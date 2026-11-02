import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
}

dependencies {
    implementation(project(":core"))

    implementation(compose.desktop.currentOs)
    implementation(compose.material3)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.jmdns)
    implementation(libs.jna)
    runtimeOnly(libs.slf4j.nop)

    testImplementation(libs.junit)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// The pure-Compose screens (theme, radar canvas, liquid orb, icons, history,
// offer sheet…) are shared verbatim with the Android app: they are copied out
// of :app at build time so there is exactly one source of truth. Everything
// that touches platform APIs lives in this module under the same package
// names, so the shared files compile unchanged.
val sharedUi = listOf(
    "DeviceNameDialog.kt",
    "FileTypeTile.kt",
    "IncomingOfferSheet.kt",
    "QrView.kt",
    "UiIcons.kt",
    "theme/Theme.kt",
    "radar/RadarCanvas.kt",
    "transfer/LiquidOrb.kt",
    "transfers/TransfersScreen.kt",
)
val syncSharedUi by tasks.registering(Sync::class) {
    from(rootProject.file("app/src/main/java/com/crossdrop/ui")) {
        include(sharedUi)
        // Only Android API in the shared set is a debug log line.
        filter { it.replace("android.util.Log.", "com.crossdrop.platform.Log.") }
    }
    into(layout.buildDirectory.dir("generated/sharedUi/com/crossdrop/ui"))
}
sourceSets.main {
    kotlin.srcDir(layout.buildDirectory.dir("generated/sharedUi"))
}
tasks.named("compileKotlin") { dependsOn(syncSharedUi) }

// Installers can only be built on the OS they target (jpackage needs the native
// toolchain: hdiutil on macOS, WiX on Windows), so the packaging tasks that exist
// follow the machine running Gradle. Compose registers only the formats that are
// valid for the host, so listing all of them below is safe on either OS.
val hostIsMac = System.getProperty("os.name").startsWith("Mac")

// Windows Installer refuses to "upgrade" to an MSI whose ProductVersion equals the installed one
// (same upgradeUuid + same version = "another version is already installed"), so the Windows
// version must move with each release. It follows APP_VERSION in Main.kt, mapped 0.x.y -> 1.x.y
// to keep the "must start at 1" rule macOS already has. Read through a provider so Gradle's
// configuration cache notices when Main.kt changes.
val windowsPackageVersion: Provider<String> = providers
    .fileContents(layout.projectDirectory.file("src/main/kotlin/com/crossdrop/Main.kt")).asText
    .map { src ->
        val m = Regex("""APP_VERSION\s*=\s*"(\d+)\.(\d+)\.(\d+)"""").find(src)
            ?: error("APP_VERSION not found in Main.kt")
        val (major, minor, patch) = m.destructured
        "${maxOf(major.toInt(), 1)}.$minor.$patch"
    }

compose.desktop {
    application {
        mainClass = "com.crossdrop.MainKt"
        jvmArgs += listOf("-Xmx512m")
        if (hostIsMac) {
            // -Xdock:* is a macOS-launcher option; any other JVM refuses to start on it.
            jvmArgs += listOf("-Xdock:name=CrossDrop", "-Dapple.awt.application.appearance=NSAppearanceNameDarkAqua")
        }

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Exe)
            packageName = "CrossDrop"
            // macOS bundle versions must start at 1.x (Windows MSI is fine with it
            // too); the in-app version, APP_VERSION in Main.kt, follows the Android app.
            packageVersion = "1.0.0"
            description = "Private, ad-free local file sharing"
            vendor = "CrossDrop"
            // java.logging: JNA (Windows keep-awake) logs through java.util.logging and fails to
            // initialise without it; nothing else on the runtime classpath pulls it into the jlink image.
            modules("java.net.http", "jdk.unsupported", "java.prefs", "java.logging")

            windows {
                iconFile.set(project.file("packaging/CrossDrop.ico"))
                menuGroup = "CrossDrop"       // Start-menu folder
                shortcut = true               // desktop shortcut
                menu = true                   // Start-menu entry
                // Installs to %LOCALAPPDATA%\CrossDrop — no admin prompt, which also
                // suits an unsigned build. Windows Firewall still asks once, on first
                // launch, whether to let CrossDrop accept connections on private networks.
                perUserInstall = true
                dirChooser = true
                msiPackageVersion = windowsPackageVersion.get()
                exePackageVersion = windowsPackageVersion.get()
                // Identifies this product to Windows Installer so a newer MSI (higher version —
                // see windowsPackageVersion) upgrades in place instead of installing beside it.
                // Generated once; NEVER change.
                upgradeUuid = "B960A8B1-78D0-4343-B88D-123040141EAD"
            }

            macOS {
                bundleID = "com.crossdrop.desktop"
                dockName = "CrossDrop"
                appCategory = "public.app-category.utilities"
                minimumSystemVersion = "12.0"
                iconFile.set(project.file("packaging/CrossDrop.icns"))
                infoPlist {
                    extraKeysRawXml = """
                        <key>NSLocalNetworkUsageDescription</key>
                        <string>CrossDrop finds nearby devices on your Wi-Fi and sends files to them directly. Nothing leaves your network.</string>
                        <key>NSBonjourServices</key>
                        <array>
                            <string>_crossdrop._tcp</string>
                            <string>_crossdrop-vault._tcp</string>
                        </array>
                    """.trimIndent()
                }
            }
        }

        buildTypes.release.proguard {
            isEnabled.set(false)
        }
    }
}
