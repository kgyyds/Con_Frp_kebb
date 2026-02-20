import org.gradle.api.JavaVersion

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kgapp.frpshellpro"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.kgapp.frpshellpro"
        minSdk = 26
        targetSdk = 34

        versionCode = 100
        versionName = "1.0"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    val githubWorkspace = System.getenv("GITHUB_WORKSPACE")
    val releaseKeystorePath = githubWorkspace?.let { "$it/release.jks" }
    val releaseKeystoreExists = releaseKeystorePath?.let { file(it).exists() } == true

    signingConfigs {
        if (releaseKeystoreExists) {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release") // CI 提供 keystore 时启用 release 签名。
        }
        getByName("debug") {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.10"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {

    // -------- Compose BOM --------
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))

    // Core Compose
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("com.google.android.material:material:1.12.0")

    // Icons
    implementation("androidx.compose.material:material-icons-extended")

    // Activity + Compose
    implementation("androidx.activity:activity-compose:1.8.2")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")

    // Coil
    implementation("io.coil-kt:coil-compose:2.6.0")

    // Debug tools
    debugImplementation("androidx.compose.ui:ui-tooling")
}
