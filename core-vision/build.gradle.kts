plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Собственные модули зрения и генерации NØRVA: чистый Kotlin, без внешних библиотек и весов.
dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
