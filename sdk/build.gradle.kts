plugins {
    id("com.android.library")
}

android {
    namespace = "com.example.toolhub.plugin"
    compileSdk = 34

    defaultConfig {
        minSdk = 30
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":aidl"))
}
