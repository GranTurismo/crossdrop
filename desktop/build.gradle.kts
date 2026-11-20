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
    implementation(libs.compose.ui.backhandler)

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
    "CdIcons.kt",
    "Components.kt",
    "DeviceNameDialog.kt",
    "FileTypeTile.kt",
    "IncomingOfferSheet.kt",
    "QrView.kt",
    "UiIcons.kt",
    "theme/Theme.kt",
    "radar/RadarCanvas.kt",
    "transfers/TransfersScreen.kt",
    "transfer/GroupTransferScreen.kt",
    "live/LiveViewerScreen.kt",
    "welcome/WelcomeScreen.kt",
    "connect/ConnectHelp.kt",
    "language/LanguageUi.kt",
)
// Platform-free transfer state (sessions, groups, trust, routing) shared the same way.
val sharedNet = listOf("TransferHub.kt")
val syncSharedUi by tasks.registering(Sync::class) {
    from(rootProject.file("app/src/main/java/com/crossdrop/ui")) {
        include(sharedUi)
        // Only Android API in the shared set is a debug log line.
        filter { it.replace("android.util.Log.", "com.crossdrop.platform.Log.") }
    }
    into(layout.buildDirectory.dir("generated/sharedUi/com/crossdrop/ui"))
}
val syncSharedNet by tasks.registering(Sync::class) {
    from(rootProject.file("app/src/main/java/com/crossdrop/net")) { include(sharedNet) }
    into(layout.buildDirectory.dir("generated/sharedNet/com/crossdrop/net"))
}
// Translations: l10n/<lang>.json is shared with Android and iOS; en.json and context.json are only the source list.
val stageL10n by tasks.registering(Sync::class) {
    from(rootProject.file("l10n")) { include("*.json"); exclude("en.json", "context.json") }
    into(layout.buildDirectory.dir("generated/l10nResources/l10n"))
}
sourceSets.main {
    kotlin.srcDir(layout.buildDirectory.dir("generated/sharedUi"))
    kotlin.srcDir(layout.buildDirectory.dir("generated/sharedNet"))
    resources.srcDir(layout.buildDirectory.dir("generated/l10nResources"))
}
tasks.named("processResources") { dependsOn(stageL10n) }
tasks.named("compileKotlin") { dependsOn(syncSharedUi, syncSharedNet) }

// Installers can only be built on the OS they target (jpackage needs the native
// toolchain: hdiutil on macOS, WiX on Windows, dpkg/rpmbuild on Linux), so the packaging tasks that exist
// follow the machine running Gradle. Compose registers only the formats that are
// valid for the host, so listing all of them below is safe on either OS.
val hostIsMac = System.getProperty("os.name").startsWith("Mac")

// Windows Installer refuses to "upgrade" to an MSI whose ProductVersion equals the installed one
// (same upgradeUuid + same version = "another version is already installed"), so the installer
// version must move with each release. It follows APP_VERSION in Main.kt, mapped 0.x.y -> 1.x.y
// to keep the "must start at 1" rule macOS has. macOS uses it too: Homebrew/Sparkle-style update
// checks and Finder's Get Info compare it, so a hardcoded value would never move. Read through a
// provider so Gradle's configuration cache notices when Main.kt changes.
val appPackageVersion: Provider<String> = providers
    .fileContents(layout.projectDirectory.file("src/main/kotlin/com/crossdrop/Main.kt")).asText
    .map { src ->
        val m = Regex("""APP_VERSION\s*=\s*"(\d+)\.(\d+)\.(\d+)"""").find(src)
            ?: error("APP_VERSION not found in Main.kt")
        val (major, minor, patch) = m.destructured
        "${maxOf(major.toInt(), 1)}.$minor.$patch"
    }

// jpackage works out the .deb's dependencies from the shared libraries it ships, but gives the .rpm none (it turns
// automatic dependencies off), so on a minimal Fedora/openSUSE the app would die with "libfontconfig.so.1: cannot
// open shared object file". The Compose plugin has no setting for jpackage's --linux-package-deps, so after
// packageRpm the rpm is built again from the app image (createDistributable), reusing the options Compose gave
// jpackage (name, version, menu entry, licence…) and adding the dependencies. Soname capabilities rather than
// package names: they resolve on Fedora and openSUSE alike. The list is what Skia (skiko) and AWT's X11 toolkit
// link against; every desktop install already has all of them.
val rpmLibraries = listOf(
    "libGL.so.1", "libfontconfig.so.1", "libfreetype.so.6", "libX11.so.6", "libXext.so.6",
    "libXi.so.6", "libXrender.so.1", "libXtst.so.6",
)
val packageRpmWithDeps by tasks.registering {
    dependsOn("createDistributable")
    val argsFile = layout.buildDirectory.file("compose/tmp/packageRpm.args.txt")
    val appImage = layout.buildDirectory.dir("compose/binaries/main/app/CrossDrop")
    val deps = rpmLibraries.joinToString(", ") { "$it()(64bit)" }
    val javaHome = System.getProperty("java.home")
    doLast {
        val original = argsFile.get().asFile
        check(original.isFile) { "Compose's jpackage arguments are not at $original" }
        // Compose writes one token per line (options bare, values quoted). With --app-image the options that describe
        // the input (jar, runtime, java options, icon, resources) are already baked into the image, and jpackage
        // refuses them.
        val tokens = original.readLines().map { it.trim().removeSurrounding("\"") }.filter { it.isNotEmpty() }
        val inputOptions = setOf("--input", "--runtime-image", "--resource-dir", "--java-options", "--main-jar", "--main-class", "--icon")
        val kept = mutableListOf<String>()
        var i = 0
        while (i < tokens.size) {
            if (tokens[i] in inputOptions) i += 2 else kept += tokens[i++]
        }
        val dest = File(kept[kept.indexOf("--dest") + 1])
        kept += listOf("--app-image", appImage.get().asFile.path, "--linux-package-deps", deps)
        val patched = File(original.parentFile, "packageRpmWithDeps.args.txt")
        patched.writeText(kept.joinToString("\n") { "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" } + "\n")
        dest.listFiles { f -> f.extension == "rpm" }?.forEach { it.delete() }   // jpackage won't overwrite
        val p = ProcessBuilder("$javaHome/bin/jpackage", "@${patched.path}").redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { "jpackage (rpm with dependencies) failed:\n$out" }
        println("rpm written with Requires: $deps")
    }
}
tasks.matching { it.name == "packageRpm" }.configureEach { finalizedBy(packageRpmWithDeps) }

compose.desktop {
    application {
        mainClass = "com.crossdrop.MainKt"
        jvmArgs += listOf("-Xmx512m")
        if (hostIsMac) {
            // -Xdock:* is a macOS-launcher option; any other JVM refuses to start on it.
            jvmArgs += listOf("-Xdock:name=CrossDrop", "-Dapple.awt.application.appearance=NSAppearanceNameDarkAqua")
        }

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "CrossDrop"
            // Derived from APP_VERSION in Main.kt (see appPackageVersion): macOS bundle versions
            // must start at 1.x, and the same value is the MSI/EXE ProductVersion.
            packageVersion = appPackageVersion.get()
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
                // Identifies this product to Windows Installer so a newer MSI (higher version —
                // see appPackageVersion) upgrades in place instead of installing beside it.
                // Generated once; NEVER change.
                upgradeUuid = "B960A8B1-78D0-4343-B88D-123040141EAD"
            }

            linux {
                // Debian package names must be lowercase; the app itself is still "CrossDrop".
                packageName = "crossdrop"
                iconFile.set(project.file("src/main/resources/icon.png"))
                shortcut = true               // application-menu entry (the .desktop file)
                menuGroup = "Network"
                appCategory = "Network"
                // jpackage writes "Maintainer: <vendor> <this>", so this is the bare address.
                debMaintainer = "crossdrop@users.noreply.github.com"
                rpmLicenseType = "ASL 2.0"
            }

            macOS {
                bundleID = "com.crossdrop.desktop"
                dockName = "CrossDrop"
                appCategory = "public.app-category.utilities"
                minimumSystemVersion = "12.0"
                iconFile.set(project.file("packaging/CrossDrop.icns"))

                // Release signing + notarization is opt-in, driven by environment variables, so a
                // plain `packageDmg` stays unsigned and needs no credentials (see desktop/RELEASE.md):
                //   MACOS_SIGN_IDENTITY       the part after "Developer ID Application: " in the certificate's
                //                             name, e.g. "Jane Doe (ABCDE12345)" — setting it turns signing on
                //   MACOS_KEYCHAIN            optional keychain path (CI uses a temporary one)
                //   MACOS_NOTARY_APPLE_ID, MACOS_NOTARY_PASSWORD (app-specific password), MACOS_TEAM_ID
                //                             used by `./gradlew :desktop:notarizeDmg`
                // Signing enables the hardened runtime; the JIT / unsigned-memory / library-validation
                // entitlements a JVM app needs come from Compose's default entitlements.
                val signIdentity = providers.environmentVariable("MACOS_SIGN_IDENTITY")
                signing {
                    sign.set(signIdentity.map { it.isNotBlank() }.orElse(false))
                    identity.set(signIdentity)
                    keychain.set(providers.environmentVariable("MACOS_KEYCHAIN"))
                }
                notarization {
                    appleID.set(providers.environmentVariable("MACOS_NOTARY_APPLE_ID"))
                    password.set(providers.environmentVariable("MACOS_NOTARY_PASSWORD"))
                    teamID.set(providers.environmentVariable("MACOS_TEAM_ID"))
                }
                infoPlist {
                    // CFBundleDocumentTypes: Finder's "Open With → CrossDrop" and dropping files on the
                    // Dock icon hand them to the app (Desktop.setOpenFileHandler), ready to send.
                    extraKeysRawXml = """
                        <key>CFBundleDocumentTypes</key>
                        <array>
                            <dict>
                                <key>CFBundleTypeName</key>
                                <string>Any file</string>
                                <key>CFBundleTypeRole</key>
                                <string>Viewer</string>
                                <key>LSHandlerRank</key>
                                <string>Alternate</string>
                                <key>LSItemContentTypes</key>
                                <array>
                                    <string>public.item</string>
                                    <string>public.data</string>
                                    <string>public.content</string>
                                </array>
                            </dict>
                        </array>
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
