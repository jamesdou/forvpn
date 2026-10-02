plugins {
    id("com.android.application")
}

android {
    namespace = "ai.forgenerative.wgswitch"
    compileSdk = 36

    defaultConfig {
        applicationId = "ai.forgenerative.wgswitch"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
