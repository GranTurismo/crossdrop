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

compose.desktop {
    application {
        mainClass = "com.crossdrop.MainKt"
        jvmArgs += listOf("-Xdock:name=CrossDrop", "-Xmx512m", "-Dapple.awt.application.appearance=NSAppearanceNameDarkAqua")

        nativeDistributions {
            targetFormats(TargetFormat.Dmg)
            packageName = "CrossDrop"
            // macOS bundle versions must start at 1.x; the in-app version (0.2.0,
            // see Main.kt) follows the Android app.
            packageVersion = "1.0.0"
            description = "Private, ad-free local file sharing"
            vendor = "CrossDrop"
            modules("java.net.http", "jdk.unsupported", "java.prefs")

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
