pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "norva-ai-mobile"

// Чистые Kotlin/JVM-модули тестируются без Android SDK.
include(":core-policy")
include(":core-inference")
include(":core-chat")
include(":core-vision")

// Android-модули подключаются, только если найден Android SDK (Android Studio создаёт local.properties).
val hasAndroidSdk = file("local.properties").exists() ||
    !System.getenv("ANDROID_HOME").isNullOrBlank() || !System.getenv("ANDROID_SDK_ROOT").isNullOrBlank()
if (hasAndroidSdk) {
    include(":engine-llama")
    include(":app")
}
