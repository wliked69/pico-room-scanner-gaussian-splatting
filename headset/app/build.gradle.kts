plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

// Sensor diagnostics are opt-in; normal RoomSplat builds keep the proven SDK surface.
val depthProbeBuild = providers.gradleProperty("roomsplat.depthProbe").orNull == "true"

android {
    namespace = "com.example.roomsplat"
    compileSdk = 35
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.example.roomsplat"
        minSdk = 35
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters.add("arm64-v8a") }
        externalNativeBuild {
            cmake { arguments.add("-DANDROID_STL=c++_static") }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    if (depthProbeBuild) {
        sourceSets.getByName("debug").manifest.srcFile("src/depthProbe/AndroidManifest.xml")
    }
}

dependencies {
    implementation(libs.spatial.ml.securemr)
    implementation(libs.spatial.ml.readback)
    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.spatial.bom))
    implementation(libs.spatial.core)
    implementation(libs.spatial.ui.platform)
    implementation(libs.spatial.ui.foundation)
    implementation(libs.spatial.ui.design)
    implementation(libs.spatial.ui.sense)
    implementation(libs.spatial.ui.tracking)
    implementation(libs.androidx.ui.tooling)
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(platform(libs.spatial.bom))
    androidTestImplementation(libs.spatial.ml.securemr)
    androidTestImplementation(libs.spatial.ml.readback)
    androidTestImplementation(libs.androidx.espresso.core)
    debugImplementation(libs.androidx.ui.tooling.preview)
}

configurations.all {
    resolutionStrategy {
        exclude("androidx.compose.ui", "ui")
        exclude("androidx.compose.ui", "ui-graphics")
        exclude("androidx.compose.ui", "ui-text")
        exclude("androidx.compose.foundation", "foundation")
    }
}

// AGP UTP uninstalls the target app after connected tests, including its private scans.
// Use explicit install --replace + am instrument on a user device; only opt in on disposable devices.
tasks.configureEach {
    if (name.startsWith("connected") && name.endsWith("AndroidTest")) {
        doFirst {
            check(providers.gradleProperty("roomsplat.allowDestructiveDeviceTests").orNull == "true") {
                "Blocked: connected Android tests uninstall RoomSplat and delete scans. Use the data-preserving pico-cli instrumentation workflow."
            }
        }
    }
}
