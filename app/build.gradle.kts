plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.openlauncher.tv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.openlauncher.tv"
        // 小米电视 4A 等国产机型常停留在 Android 6.0（API 23）
        minSdk = 23
        targetSdk = 34
        versionCode = 9
        versionName = "1.2.4"
    }

    signingConfigs {
        // 固定签名（仓库内 keystore/opentv.p12）。之前用 CI 自动生成的 debug 签名，
        // 每次构建密钥都不同，导致新旧版本无法覆盖安装。
        create("shared") {
            storeFile = rootProject.file("keystore/opentv.p12")
            storePassword = "opentv2026"
            keyAlias = "opentv"
            keyPassword = "opentv2026"
            storeType = "PKCS12"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // AndroidX & TV Leanback（已移除 material / constraintlayout：本工程未使用）
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.leanback:leanback:1.0.0")

    // Lifecycle & Coroutines
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Room Database (Local Persistence)
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // DataStore Preferences
    implementation("androidx.datastore:datastore-preferences:1.0.0")
}
