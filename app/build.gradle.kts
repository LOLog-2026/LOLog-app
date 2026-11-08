import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// 서버 주소·테스트 토큰은 local.properties에 둬요(커밋 안 함, CLAUDE.md 11번)
//   LOLOG_BASE_URL=http://10.0.2.2:8080/
//   LOLOG_TEST_TOKEN=...
//   LOLOG_USE_MOCK=false
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val lologBaseUrl = (localProps.getProperty("LOLOG_BASE_URL") ?: "http://10.0.2.2:8080/").let { if (it.endsWith("/")) it else "$it/" }
val lologTestToken = localProps.getProperty("LOLOG_TEST_TOKEN") ?: ""
// 목업 켜기·끄기. 기본은 목업(true). 실제 서버·녹음으로 시험할 때만 local.properties에 LOLOG_USE_MOCK=false
val lologUseMock = (localProps.getProperty("LOLOG_USE_MOCK") ?: "true").trim().toBoolean()

android {
    namespace = "com.lolog.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lolog.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        // true: 앱 안의 목업 데이터로 동작 (백엔드 없이 에뮬레이터에서 바로 실행)
        // false: LOLOG_BASE_URL의 실제 서버(Nginx)로 요청
        buildConfigField("boolean", "USE_MOCK", "$lologUseMock")
        // 기본값: 에뮬레이터에서 내 PC의 localhost는 10.0.2.2
        buildConfigField("String", "LOLOG_BASE_URL", "\"$lologBaseUrl\"")
        // 로그인 전 시험용 토큰. 비어 있으면 안 씀
        buildConfigField("String", "LOLOG_TEST_TOKEN", "\"$lologTestToken\"")

        // sherpa-onnx .so는 arm64-v8a만 넣어요(지원 님 stt/README.md)
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    // STT 모델(.onnx)은 압축하지 않아야 로딩이 느려지지 않아요(지원 님 stt/README.md)
    androidResources { noCompress += listOf("onnx") }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.coil.compose)
    debugImplementation(libs.androidx.ui.tooling)
    testImplementation(libs.junit)
}
