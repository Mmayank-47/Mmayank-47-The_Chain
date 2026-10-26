plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core"))
    implementation(project(":transport"))
    implementation(project(":verify"))
    implementation(project(":trust"))
    implementation(project(":hazard"))
    implementation(project(":battery"))
    implementation(project(":crypto-fake"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(testFixtures(project(":core")))
}
