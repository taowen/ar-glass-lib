plugins {
    id("com.android.library")
}

android {
    namespace = "com.taowen.arglass"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild { cmake { cppFlags += listOf("-std=c++20", "-Wall", "-Wextra", "-Werror") } }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }
}
