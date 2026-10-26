plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.zest.island"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.zest.island"
        minSdk = 31
        targetSdk = 34
        versionCode = 130
        versionName = "1.3.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes {
        release {
            // Opsional: assembleRelease (kecil & tanpa kode tak terpakai), ditandatangani kunci debug agar cocok dengan priv-app.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false }
}
