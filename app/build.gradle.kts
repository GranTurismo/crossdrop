import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.crossdrop"
    compileSdk = 36

    val keystoreProps = Properties()
    val keystorePropsFile = rootProject.file("key.properties")
    if (keystorePropsFile.exists()) keystoreProps.load(FileInputStream(keystorePropsFile))

    signingConfigs {
        create("release") {
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
            storeFile = keystoreProps.getProperty("storeFile")?.let { rootProject.file(it) }
            storePassword = keystoreProps.getProperty("storePassword")
        }
    }

    defaultConfig {
        applicationId = "com.crossdrop"
        minSdk = 29
        targetSdk = 36
        versionCode = 10
        versionName = "0.8.0"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Translations: l10n/<lang>.json is shared by every platform. The shipped ones are staged as assets
    // (en.json and context.json are only the source list for translators).
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/l10nAssets"))

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin.compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)

    lint {
        // mipmap-anydpi-v26 must keep its qualifier: AAPT2 does not resolve a bare mipmap-anydpi here.
        disable += listOf("ObsoleteSdkInt", "UnusedAttribute")
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystorePropsFile.exists()) signingConfig = signingConfigs.getByName("release")
        }
    }
}

val stageL10n by tasks.registering(Sync::class) {
    from(rootProject.file("l10n")) { include("*.json"); exclude("en.json", "context.json") }
    into(layout.buildDirectory.dir("generated/l10nAssets/l10n"))
}
tasks.named("preBuild") { dependsOn(stageL10n) }

dependencies {
    implementation(project(":core"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.service)
    implementation(libs.documentfile)
    // In-app camera (live camera sessions): preview, photo and video capture.
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.video)
    implementation(libs.camera.view)
    // "Scan a code": decodes the other phone's private-link code.
    implementation(libs.zxing.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    debugImplementation(libs.compose.ui.tooling)
}
