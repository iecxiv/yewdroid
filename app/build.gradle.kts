plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.chaquo.python")
}

android {
    namespace = "iecxiv.yewdroid"
    compileSdk = 35
    defaultConfig {
        applicationId = "iecxiv.yewdroid"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    sourceSets.getByName("main") {
        jniLibs.srcDir(layout.buildDirectory.dir("generated/quickjs/jniLibs"))
        assets.srcDir(layout.buildDirectory.dir("generated/quickjs/assets"))
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += "**/libqjs.so"
        }
    }
}

val quickJsSdk = androidComponents.sdkComponents.sdkDirectory
val buildQuickJs by tasks.registering(Exec::class) {
    group = "build"
    description = "Build the Android QuickJS executable used by yt-dlp"
    workingDir(rootProject.projectDir)
    commandLine("bash", rootProject.file("tools/build-quickjs-android.sh").absolutePath)
    doFirst {
        environment("ANDROID_HOME", quickJsSdk.get().asFile.absolutePath)
    }
}
tasks.named("preBuild") { dependsOn(buildQuickJs) }

chaquopy {
    defaultConfig {
        version = "3.13"
        pip {
            install("yewtube")
            install("yt-dlp==2026.10.26")
            install("yt-dlp-ejs==0.8.0")
        }
    }
}

dependencies {
    val media3 = "1.5.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-session:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:${media3}")
    implementation("androidx.media3:media3-exoplayer-dash:${media3}")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.3")
}
