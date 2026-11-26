plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.chaquo.python")
}

val enableFfmpeg = providers.gradleProperty("enableFfmpeg").map { it.toBooleanStrict() }.getOrElse(false)

// Sources fetched by tools/prepare-media-sources.py; each can be overridden by a property or variable.
val mediaSourcesDir = rootProject.layout.projectDirectory.dir("build/media-sources/verified").asFile
fun verified(name: String): String = mediaSourcesDir.resolve(name).absolutePath
val explicitFfmpegSource = providers.gradleProperty("ffmpegSource").orElse(providers.environmentVariable("FFMPEG_SOURCE"))
val ffmpegSource = explicitFfmpegSource.orElse(verified("ffmpeg-9.1"))
val lameSource = providers.gradleProperty("lameSource").orElse(providers.environmentVariable("LAME_SOURCE")).orElse(verified("lame-3.100"))
val oggSource = providers.gradleProperty("oggSource").orElse(providers.environmentVariable("OGG_SOURCE")).orElse(verified("libogg-1.3.6"))
val vorbisSource = providers.gradleProperty("vorbisSource").orElse(providers.environmentVariable("VORBIS_SOURCE")).orElse(verified("libvorbis-1.3.7"))
val ffmpegNdk = providers.gradleProperty("ffmpegNdk").orElse(providers.environmentVariable("ANDROID_NDK_HOME"))

// FFmpeg binaries built by tools/build-ffmpeg-android.sh; once they exist they are packaged by every build.
val ffmpegAbis = listOf("arm64-v8a", "x86_64")
val ffmpegLibsDir = layout.buildDirectory.dir("generated/ffmpeg/jniLibs")
fun ffmpegBuilt(): Boolean = ffmpegAbis.all { abi ->
    listOf("libffmpeg.so", "libffprobe.so").all { ffmpegLibsDir.get().asFile.resolve("$abi/$it").isFile }
}
val packageFfmpeg = enableFfmpeg || ffmpegBuilt()

fun missingTools(vararg names: String): List<String> = names.filter { name ->
    try {
        val process = ProcessBuilder("bash", "-c", "command -v $name").redirectErrorStream(true).start()
        process.inputStream.readBytes()
        process.waitFor() != 0
    } catch (_: Exception) {
        true
    }
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
        if (packageFfmpeg) {
            jniLibs.srcDir(layout.buildDirectory.dir("generated/ffmpeg/jniLibs"))
            assets.srcDir(layout.buildDirectory.dir("generated/ffmpeg/assets"))
        }
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += "**/libqjs.so"
            if (packageFfmpeg) {
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

/** The newest Linux NDK installed in the Android SDK (Android Studio's SDK Manager), if any. */
fun installedNdk(): String? {
    fun rank(name: String) = name.split('.').take(3).fold(0L) { total, part -> total * 1000 + (part.toLongOrNull() ?: 0L) }
    return quickJsSdk.get().asFile.resolve("ndk").listFiles()
        ?.filter { it.resolve("toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-ar").isFile }
        ?.maxByOrNull { rank(it.name) }
        ?.absolutePath
}

if (enableFfmpeg) {
    val prepareMediaSources by tasks.registering(Exec::class) {
        group = "build"
        description = "Download and verify the FFmpeg, LAME, libogg and libvorbis sources"
        workingDir(rootProject.projectDir)
        commandLine("python3", rootProject.file("tools/prepare-media-sources.py").absolutePath)
        onlyIf { !ffmpegBuilt() && !mediaSourcesDir.exists() && !explicitFfmpegSource.isPresent }
        doFirst {
            val missing = missingTools("python3", "gpg")
            if (missing.isNotEmpty()) {
                throw GradleException("Missing tools: ${missing.joinToString()}. Install them with: sudo apt install python3 gnupg")
            }
        }
    }
    val buildFfmpeg by tasks.registering(Exec::class) {
        group = "build"
        description = "Build Android FFmpeg and ffprobe with LAME, libogg and libvorbis from the verified sources"
        workingDir(rootProject.projectDir)
        commandLine("bash", rootProject.file("tools/build-ffmpeg-android.sh").absolutePath)
        dependsOn(prepareMediaSources)
        // Already built: delete app/build/generated/ffmpeg to force a rebuild.
        onlyIf { !ffmpegBuilt() }
        doFirst {
            val missing = missingTools("make", "pkg-config", "python3")
            if (missing.isNotEmpty()) {
                throw GradleException("Missing tools: ${missing.joinToString()}. Install them with: sudo apt install make pkg-config python3")
            }
            val ndk = ffmpegNdk.orNull ?: installedNdk()
                ?: throw GradleException("No Linux NDK found. Install one from Android Studio (SDK Manager > SDK Tools > NDK) or define -PffmpegNdk")
            val ndkDir = rootProject.file(ndk)
            if (!ndkDir.resolve("toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-ar").isFile) {
                throw GradleException("Invalid Linux NDK for the FFmpeg script: $ndk")
            }
            val sources = mapOf(
                "FFMPEG_SOURCE" to rootProject.file(ffmpegSource.get()),
                "LAME_SOURCE" to rootProject.file(lameSource.get()),
                "OGG_SOURCE" to rootProject.file(oggSource.get()),
                "VORBIS_SOURCE" to rootProject.file(vorbisSource.get())
            )
            sources.forEach { (variable, dir) ->
                if (!dir.resolve("configure").isFile) {
                    throw GradleException("$variable has no configure script: $dir")
                }
                environment(variable, dir.absolutePath)
            }
            environment("ANDROID_NDK_HOME", ndkDir.absolutePath)
            environment("JOBS", Runtime.getRuntime().availableProcessors().coerceIn(2, 6).toString())
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
