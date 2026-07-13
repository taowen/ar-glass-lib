plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.taowen.arglass.demo"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.taowen.arglass.demo"
        minSdk = 26
        targetSdk = 36
        versionCode = 10000
        versionName = "1.0.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies { implementation(project(":library")) }
