plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.chaquo.python")
}

val enableFfmpeg = providers.gradleProperty("enableFfmpeg").map { it.toBooleanStrict() }.getOrElse(false)
val ffmpegSource = providers.gradleProperty("ffmpegSource").orElse(providers.environmentVariable("FFMPEG_SOURCE"))
val lameSource = providers.gradleProperty("lameSource").orElse(providers.environmentVariable("LAME_SOURCE"))
val ffmpegNdk = providers.gradleProperty("ffmpegNdk").orElse(providers.environmentVariable("ANDROID_NDK_HOME"))

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
        if (enableFfmpeg) {
            jniLibs.srcDir(layout.buildDirectory.dir("generated/ffmpeg/jniLibs"))
            assets.srcDir(layout.buildDirectory.dir("generated/ffmpeg/assets"))
        }
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += "**/libqjs.so"
            if (enableFfmpeg) {
                keepDebugSymbols += listOf("**/libffmpeg.so", "**/libffprobe.so")
            }
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

if (enableFfmpeg) {
    val buildFfmpeg by tasks.registering(Exec::class) {
        group = "build"
        description = "Build Android FFmpeg and ffprobe with LAME from supplied sources"
        workingDir(rootProject.projectDir)
        commandLine("bash", rootProject.file("tools/build-ffmpeg-android.sh").absolutePath)
        doFirst {
            val ffmpeg = ffmpegSource.orNull ?: throw GradleException("Define -PffmpegSource or FFMPEG_SOURCE")
            val lame = lameSource.orNull ?: throw GradleException("Define -PlameSource or LAME_SOURCE")
            val ndk = ffmpegNdk.orNull ?: throw GradleException("Define -PffmpegNdk or ANDROID_NDK_HOME")
            val ffmpegDir = rootProject.file(ffmpeg)
            val lameDir = rootProject.file(lame)
            val ndkDir = rootProject.file(ndk)
            if (!ffmpegDir.resolve("configure").isFile || !lameDir.resolve("configure").isFile) {
                throw GradleException("FFmpeg and LAME require source trees extracted with configure")
            }
            if (!ndkDir.resolve("toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-ar").isFile) {
                throw GradleException("Invalid Linux NDK for the FFmpeg script")
            }
            environment("FFMPEG_SOURCE", ffmpegDir.absolutePath)
            environment("LAME_SOURCE", lameDir.absolutePath)
            environment("ANDROID_NDK_HOME", ndkDir.absolutePath)
        }
    }
    tasks.named("preBuild") { dependsOn(buildFfmpeg) }
}

chaquopy {
    defaultConfig {
        version = "3.13"
        pip {
            install("yewtube")
            install("yt-dlp==2026.11.23")
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
