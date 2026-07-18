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
        versionCode = 10004
        versionName = "1.0.4"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets.getByName("main").jniLibs.srcDir("vendorJniLibs")
}

dependencies { implementation(project(":library")) }
