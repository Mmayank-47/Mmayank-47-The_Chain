plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core"))
    api(project(":transport"))
    api(project(":merge"))
    api(project(":identity"))
    api(project(":parser"))
    api(project(":verify"))
    api(project(":trust"))
    api(project(":battery"))
    api(project(":matching"))
    api(project(":privacy"))
    api(project(":ux"))
    implementation(libs.json)
    testImplementation(project(":crypto-fake"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(testFixtures(project(":core")))
}
