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

rootProject.name = "the-chain"

// Kotlin modules live under android/ so they do not collide with the Python reference packages.
val modules = listOf(
    "core",
    "crypto-fake",
    "identity",
    "merge",
    "transport",
    "parser",
    "verify",
    "hazard",
    "trust",
    "battery",
    "matching",
    "privacy",
    "ux",
    "redteam",
    "node",
    "sim",
    "guards",
    "app",
)
modules.forEach { name ->
    include(":$name")
    project(":$name").projectDir = file("android/$name")
}
