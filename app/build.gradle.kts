plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.easycast.tv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.easycast.tv"
        minSdk = 26          // Android 8.0
        targetSdk = 26
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.media3:media3-exoplayer:1.1.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.1.1")
    implementation("androidx.media3:media3-datasource:1.1.1")
}
