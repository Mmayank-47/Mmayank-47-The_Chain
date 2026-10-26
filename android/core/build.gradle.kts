plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-test-fixtures`
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testFixturesApi(libs.json)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
