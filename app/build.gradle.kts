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
        versionCode = 7
        versionName = "1.4.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets.named("main") {
        kotlin.directories += "src/main/kotlin"
    }

    testOptions.unitTests.isIncludeAndroidResources = true

    buildTypes {
        create("standalone") {
            initWith(getByName("release"))
            applicationIdSuffix = ".stable"
            matchingFallbacks += "release"
            manifestPlaceholders["appLabel"] = "Dyktafon 1.4"
        }
    }

    defaultConfig.manifestPlaceholders["appLabel"] = "Dyktafon"
}

dependencies {
    implementation("androidx.work:work-runtime:2.11.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.work:work-testing:2.11.2")
}
