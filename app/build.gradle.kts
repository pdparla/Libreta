plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.parla.libreta"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.parla.libreta"
        minSdk = 19
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    lint {
        abortOnError = true
    }
}

dependencies {
    implementation(project(":core"))
}
