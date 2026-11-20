plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

android {
    namespace = "dev.hayrx.code"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.hayrx.code"
        minSdk = 26
        targetSdk = 35

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        versionCode = 4
        versionName = "0.4.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.10.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
}


chaquopy {
    defaultConfig {
        version = "3.13"
    }
}
