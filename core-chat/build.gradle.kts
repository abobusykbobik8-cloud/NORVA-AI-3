plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(project(":core-policy"))
    api(project(":core-inference"))
    api(libs.kotlinx.coroutines.core)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    useJUnitPlatform()
}
