plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "pl.lisu188.speechrecorder"
    compileSdk = 37

    defaultConfig {
        applicationId = "pl.lisu188.speechrecorder"
        minSdk = 29
        targetSdk = 37
        versionCode = 14
        versionName = "1.8.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets.named("main") {
        kotlin.directories += "src/main/kotlin"
    }

    testOptions.unitTests.isIncludeAndroidResources = true

    buildFeatures {
        compose = true
    }

    buildTypes {
        create("standalone") {
            initWith(getByName("release"))
            applicationIdSuffix = ".stable"
            matchingFallbacks += "release"
            manifestPlaceholders["appLabel"] = "Dyktafon 1.8"
        }
    }

    defaultConfig.manifestPlaceholders["appLabel"] = "Dyktafon"
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material3:material3-adaptive-navigation-suite")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.work:work-runtime:2.11.2")
    implementation("com.google.android.gms:play-services-auth:22.0.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.work:work-testing:2.11.2")
}
