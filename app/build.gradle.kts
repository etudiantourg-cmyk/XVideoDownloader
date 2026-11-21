plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.example.xvideodownloader"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.example.xvideodownloader"
        minSdk = 23
        targetSdk = 32
        versionCode = 8
        versionName = "1.1.5"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
