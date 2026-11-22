plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val nativeLibs = layout.buildDirectory.dir("generated/tinyasr/jni")
val rustBuild = layout.buildDirectory.dir("tinyasr")
val nativeOutput = nativeLibs.map { it.file("arm64-v8a/libvoicekb.so") }
val buildTinyAsr = tasks.register<Exec>("buildTinyAsr") {
    val script = rootProject.file("scripts/build-tinyasr.sh")
    val source = rootProject.file("vendor/tinyasr")
    val bridge = file("src/main/cpp/tinyasr_jni.c")
    inputs.files(script, bridge, source.resolve("Cargo.toml"), source.resolve("Cargo.lock"))
    inputs.dir(source.resolve("src"))
    inputs.dir(source.resolve("include"))
    outputs.file(nativeOutput)
    commandLine("bash", script, source, bridge, rustBuild.get().asFile, nativeOutput.get().asFile)
}

android {
    namespace = "ly.anoma.hex"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.voicekb.android"
        minSdk = 31
        targetSdk = 36
        versionCode = 12
        versionName = "0.1.11"
        ndk { abiFilters += "arm64-v8a" }
        testInstrumentationRunner = "ly.anoma.hex.DeviceChecks"
    }

    packaging.resources.excludes += "DebugProbesKt.bin"
    buildFeatures { compose = true }
    androidResources { noCompress += listOf("hextm", "valid", "capitals", "gzip") }
    sourceSets["main"].jniLibs.directories.add(nativeLibs.get().asFile.path)
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.matching { it.name.endsWith("JniLibFolders") && it.name.startsWith("merge") }
    .configureEach { dependsOn(buildTinyAsr) }

dependencies {
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.emoji2:emoji2-emojipicker:1.7.0")
    implementation("org.pytorch:executorch-android:1.5.1")
    testImplementation("junit:junit:4.13.2")
}
