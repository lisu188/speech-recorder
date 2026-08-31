plugins {
    id("com.android.application")
}

android {
    namespace = "pl.lisu188.speechrecorder"
    compileSdk = 37

    defaultConfig {
        applicationId = "pl.lisu188.speechrecorder"
        minSdk = 29
        targetSdk = 37
        versionCode = 6
        versionName = "1.4.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets.named("main") {
        kotlin.directories += "src/main/kotlin"
    }
}

dependencies {
    implementation("androidx.work:work-runtime:2.11.2")
}
