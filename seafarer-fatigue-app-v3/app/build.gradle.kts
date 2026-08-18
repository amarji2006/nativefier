plugins {
    id("com.android.application")
}

android {
    namespace = "com.maritimefatigue.ai"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.maritimefatigue.ai"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "4.0.1"
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.health.connect:connect-client:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
}
