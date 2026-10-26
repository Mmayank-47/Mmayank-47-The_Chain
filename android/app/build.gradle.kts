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

    signingConfigs {
        // Local release smoke: reuse the debug keystore when present.
        val debugStore = signingConfigs.findByName("debug")
        create("releaseLocal") {
            if (debugStore != null) {
                storeFile = debugStore.storeFile
                storePassword = debugStore.storePassword
                keyAlias = debugStore.keyAlias
                keyPassword = debugStore.keyPassword
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "DEV_COMPONENTS", "false")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            buildConfigField("boolean", "DEV_COMPONENTS", "false")
            signingConfig = signingConfigs.getByName("releaseLocal")
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
    implementation(project(":identity"))
    implementation(project(":hazard"))
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.runtime)
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation(libs.nearby)
    implementation("org.maplibre.gl:android-sdk:13.7.1")
    implementation(libs.work)
    implementation(libs.coroutines.android)
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

tasks.named("check") {
    dependsOn("assertShippingClasspath")
}
