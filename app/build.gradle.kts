import java.time.Duration

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

        // The tablet is armeabi-v7a; leaving out Conscrypt's other native builds saves several MB.
        ndk {
            abiFilters += "armeabi-v7a"
        }
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    lint {
        abortOnError = true
        disable += listOf(
            // The SM-T113 has a hardware menu key, so Android hides the overflow button: "ifRoom" items would vanish.
            "AlwaysShowAction",
            // Built only for this tablet (armeabi-v7a).
            "ChromeOsAbiSupport",
        )
    }
}

// A test waiting on the network must fail, not hang the build.
tasks.withType<Test>().configureEach {
    timeout.set(Duration.ofMinutes(3))
}

dependencies {
    implementation(project(":core"))
    implementation(libs.okhttp)
    implementation(libs.conscrypt.android)
    implementation(libs.markwon.core)
    implementation(libs.markwon.editor)
    implementation(libs.markwon.image)
    implementation(libs.markwon.strikethrough)
    implementation(libs.markwon.tables)
    implementation(libs.markwon.latex)
    implementation(libs.markwon.inline.parser)
    implementation(libs.qrcodegen)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
    testImplementation(libs.org.json)
}
