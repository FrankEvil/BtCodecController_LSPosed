plugins {
    id("com.android.application")
}

android {
    namespace = "com.frank.btcodec"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.frank.btcodec"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:101.0.1")
}
