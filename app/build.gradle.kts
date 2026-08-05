import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// 从 local.properties 读取 API Key（避免硬编码到源码中）
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "com.popkter.robotface"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.popkter.robotface"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // API Keys 从 local.properties 注入
        buildConfigField("String", "LLM_API_KEY", "\"${localProperties.getProperty("LLM_API_KEY", "")}\"")
        buildConfigField("String", "TTS_API_KEY", "\"${localProperties.getProperty("TTS_API_KEY", "")}\"")
        buildConfigField("String", "ASR_API_KEY", "\"${localProperties.getProperty("ASR_API_KEY", "")}\"")

        // UDP(x5_server 联调):默认 127.0.0.1:8765;模拟器连宿主机填 10.0.2.2,真机填 x5 所在机 LAN IP
        buildConfigField("String", "UDP_HOST", "\"${localProperties.getProperty("UDP_HOST", "127.0.0.1")}\"")
        buildConfigField("int", "UDP_PORT", localProperties.getProperty("UDP_PORT", "8765"))
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        buildConfig = true
    }
}

dependencies {

    implementation(project(":robot"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.mlkit.face.detection)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}