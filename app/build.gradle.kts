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
        versionCode = 4
        versionName = "1.3-dev"
        ndk { abiFilters += "arm64-v8a" }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = true }
}

dependencies {
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
