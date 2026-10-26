plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "org.thechain.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.thechain.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "DEV_COMPONENTS", "false")
        }
        release {
            isMinifyEnabled = true
            buildConfigField("boolean", "DEV_COMPONENTS", "false")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":node"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
}

tasks.register("assertShippingClasspath") {
    dependsOn("preBuild")
    doLast {
        val names = configurations.getByName("releaseRuntimeClasspath").incoming.resolutionResult.allComponents
            .map { it.id.displayName }
        val banned = names.filter { it.contains("crypto-fake") || it.contains("project :sim") }
        if (banned.isNotEmpty()) {
            error("shipping runtime can see ${banned.joinToString()}")
        }
    }
}
