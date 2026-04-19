plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.sheltron.captioner"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.sheltron.captioner"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            // Keep APK small — target 64-bit ARM only. Add "armeabi-v7a" if you need 32-bit support.
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-fexceptions", "-frtti")
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        // Stable debug signing key committed at app/debug.keystore.
        // Same key across local and CI builds so debug APKs update in place.
        val stableDebugKeystore = rootProject.file("app/debug.keystore")
        if (stableDebugKeystore.exists()) {
            getByName("debug") {
                storeFile = stableDebugKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            signingConfig = signingConfigs.getByName("debug")
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
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// Clone whisper.cpp into app/src/main/cpp/whisper.cpp/ before the native build.
val cloneWhisperCpp by tasks.registering(Exec::class) {
    val dest = project.file("src/main/cpp/whisper.cpp")
    outputs.dir(dest)
    onlyIf { !dest.resolve("CMakeLists.txt").exists() }
    commandLine(
        "git", "clone",
        "--depth", "1",
        "--branch", "v1.7.2",
        "https://github.com/ggerganov/whisper.cpp.git",
        dest.absolutePath
    )
}

tasks.configureEach {
    if (name.startsWith("externalNativeBuild") || name.startsWith("generateJsonModel")) {
        dependsOn(cloneWhisperCpp)
    }
}

dependencies {
    // Core
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    // Compose
    val composeBom = platform("androidx.compose:compose-bom:2024.08.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Encrypted preferences for local settings storage
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // On-device LLM via MediaPipe (Gemma). Broader device support than AICore.
    implementation("com.google.mediapipe:tasks-genai:0.10.24")

    // Vosk speech recognition
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
}
