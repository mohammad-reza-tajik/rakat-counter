plugins {
    id("com.android.application")
}

android {
    namespace = "com.rakat.counter"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.rakat.counter"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // امضا با کلید debug تا APK قابل نصب باشد
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
