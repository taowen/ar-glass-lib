plugins {
    id("com.android.application")
}

android {
    namespace = "com.taowen.arglass.demo"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.taowen.arglass.demo"
        minSdk = 26
        targetSdk = 36
        versionCode = 10003
        versionName = "1.0.3"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies { implementation(project(":library")) }
