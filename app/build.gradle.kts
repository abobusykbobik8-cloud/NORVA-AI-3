plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "ai.norva.app"
    ndkVersion = "27.2.12479018"
    compileSdk = 35

    defaultConfig {
        applicationId = "ai.norva.mobile"
        val sideBySide = providers.gradleProperty("norva.previewInstall").orNull == "true"
        if (sideBySide) applicationIdSuffix = ".preview"
        manifestPlaceholders["appLabel"] = if (sideBySide) "NØRVA AI · Тест" else "@string/app_name"
        minSdk = 29
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 7
        versionName = "1.4-beta-local"
        ndk { abiFilters += if (providers.gradleProperty("norva.emulator").orNull == "true") "x86_64" else "arm64-v8a" }
    }
    packaging { jniLibs.useLegacyPackaging = true }
    sourceSets["main"].jniLibs.srcDir(layout.buildDirectory.dir("generated/diffusion"))
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = true }
}

dependencies {
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    implementation(project(":core-chat"))
    implementation(project(":core-vision"))
    implementation(project(":engine-llama"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
}

// SDK-managed CMake builds the CPU renderer as an independent PIE executable.
// Its private ggml and C++ runtime cannot collide with the chat JNI library.
val diffAbi = if (providers.gradleProperty("norva.emulator").orNull == "true") "x86_64" else "arm64-v8a"
val nativeRoot = layout.buildDirectory.dir("diffusion/$diffAbi")
val mediaBin = layout.buildDirectory.dir("generated/diffusion/$diffAbi")
val toolExt = if (System.getProperty("os.name").startsWith("Windows")) ".exe" else ""
val cmakeBin = android.sdkDirectory.resolve("cmake/3.22.1/bin")
val prepareMedia = tasks.register<Exec>("configureMediaRenderer") {
    inputs.files(file("src/main/diffusion/CMakeLists.txt"), rootProject.file("third_party/stable-diffusion.cpp/.norva-revision"))
    outputs.file(nativeRoot.map { it.file("build.ninja") })
    commandLine(cmakeBin.resolve("cmake$toolExt"), "-S", file("src/main/diffusion"), "-B", nativeRoot.get().asFile,
        "-G", "Ninja", "-DCMAKE_MAKE_PROGRAM=" + cmakeBin.resolve("ninja$toolExt"),
        "-DCMAKE_TOOLCHAIN_FILE=" + android.sdkDirectory.resolve("ndk/${android.ndkVersion}/build/cmake/android.toolchain.cmake"),
        "-DANDROID_ABI=$diffAbi", "-DANDROID_PLATFORM=android-29", "-DANDROID_STL=c++_static",
        "-DCMAKE_BUILD_TYPE=Release", "-DNORVA_BIN_DIR=" + mediaBin.get().asFile)
}
val buildMedia = tasks.register<Exec>("buildMediaRenderer") {
    dependsOn(prepareMedia)
    inputs.dir(file("src/main/diffusion"))
    inputs.file(rootProject.file("third_party/stable-diffusion.cpp/.norva-revision"))
    outputs.file(mediaBin.map { it.file("libnorva_diffusion.so") })
    commandLine(cmakeBin.resolve("cmake$toolExt"), "--build", nativeRoot.get().asFile, "--target", "norva_media", "--parallel", "2")
}
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }.configureEach { dependsOn(buildMedia) }
