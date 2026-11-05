plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Optional ASCII output directory for Gradle's Windows unit-test worker.
providers.gradleProperty("verividBuildRoot").orNull?.let { layout.buildDirectory.set(file(it)) }

android {
    namespace = "com.verivid.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.verivid.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        val apiUrl = providers.gradleProperty("verividApiBaseUrl").orElse("http://10.0.2.2:8000/").get()
        buildConfigField("String", "API_BASE_URL", "\"${apiUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    signingConfigs.getByName("debug") {
        providers.gradleProperty("verividDebugKeystore").orNull?.let { storeFile = file(it) }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.8" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation(platform("androidx.compose:compose-bom:2024.02.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.6.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
