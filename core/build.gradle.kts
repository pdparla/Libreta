import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin/JVM: the domain and the sync engine must not depend on Android so they run in plain JVM tests.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.animalsniffer)
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

// The tablet runs Android 4.4 (API 19), whose Java class library is roughly Java 7. Animal Sniffer checks the
// compiled main classes against the API 19 signature, so calling something the tablet lacks fails the build. That
// includes JDK classes (java.time) and the JDK 8 methods Kotlin exposes on its collections (Map.getOrDefault).
// Android lint cannot do this: it does not run NewApi on JVM modules. Tests run on the regular JDK.
animalsniffer {
    sourceSets = listOf(project.sourceSets.main.get())
}

// The app consumes this jar, so building the app always runs the check.
tasks.jar {
    dependsOn(tasks.named("animalsnifferMain"))
}

dependencies {
    signature(libs.android.api19.signature) { artifact { type = "signature" } }
    testImplementation(libs.junit)
}
