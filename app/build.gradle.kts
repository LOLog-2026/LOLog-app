import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// FCM: app/google-services.json이 있을 때만 Firebase 설정을 넣어요. 이 파일은 커밋하지 않아요(.gitignore).
// 파일이 없는 PC에서도 빌드는 되고, 그때는 푸시만 꺼져요(PushTokens가 Firebase가 없으면 건너뜀).
// 파일은 Firebase 콘솔 → 프로젝트 설정 → 내 앱(com.lolog.app)에서 받아요.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

// 서버 주소·테스트 토큰은 local.properties에 둬요(커밋 안 함, CLAUDE.md 11번)
//   LOLOG_BASE_URL=http://10.0.2.2:8080/          debug 빌드가 부르는 주소
//   LOLOG_TEST_TOKEN=...                          debug 빌드에서만 써요
//   LOLOG_USE_MOCK=false                          debug 빌드에서만 써요
//   LOLOG_RELEASE_BASE_URL=https://...            release 주소를 바꿀 때만. 없으면 배포 주소
// release 서명(키스토어는 커밋 안 함, .gitignore의 *.jks)
//   LOLOG_KEYSTORE_FILE=/절대/경로/lolog-release.jks
//   LOLOG_KEYSTORE_PASSWORD=...
//   LOLOG_KEY_ALIAS=lolog
//   LOLOG_KEY_PASSWORD=...
// 소셜 로그인 키(debug·release 같이 써요). 비어 있으면 그 회사 버튼은 "준비 중"
//   LOLOG_GOOGLE_WEB_CLIENT_ID=...                구글 "웹" 클라이언트 ID(Android 클라이언트 ID 아님)
//   LOLOG_NAVER_CLIENT_ID=...
//   LOLOG_NAVER_CLIENT_SECRET=...
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun withSlash(url: String) = url.trim().let { if (it.endsWith("/")) it else "$it/" }
val lologBaseUrl = withSlash(localProps.getProperty("LOLOG_BASE_URL") ?: "http://10.0.2.2:8080/")
val lologTestToken = localProps.getProperty("LOLOG_TEST_TOKEN") ?: ""
// 목업 켜기·끄기. 기본은 목업(true). 실제 서버·녹음으로 시험할 때만 local.properties에 LOLOG_USE_MOCK=false
val lologUseMock = (localProps.getProperty("LOLOG_USE_MOCK") ?: "true").trim().toBoolean()
// release는 PC마다 다른 개발용 값을 따라가지 않아요. 배포 주소는 deployment.md 6장
val lologReleaseBaseUrl = withSlash(localProps.getProperty("LOLOG_RELEASE_BASE_URL") ?: "https://lolog.java21.net/")
val keystoreFile = localProps.getProperty("LOLOG_KEYSTORE_FILE")?.trim()?.let { file(it) }
val hasReleaseSigning = keystoreFile != null && keystoreFile.exists()
fun localString(key: String) = (localProps.getProperty(key) ?: "").trim()

android {
    namespace = "com.lolog.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lolog.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        // sherpa-onnx .so는 arm64-v8a만 넣어요(지원 님 stt/README.md)
        ndk { abiFilters += listOf("arm64-v8a") }

        buildConfigField("String", "LOLOG_GOOGLE_WEB_CLIENT_ID", "\"${localString("LOLOG_GOOGLE_WEB_CLIENT_ID")}\"")
        buildConfigField("String", "LOLOG_NAVER_CLIENT_ID", "\"${localString("LOLOG_NAVER_CLIENT_ID")}\"")
        buildConfigField("String", "LOLOG_NAVER_CLIENT_SECRET", "\"${localString("LOLOG_NAVER_CLIENT_SECRET")}\"")
    }

    signingConfigs {
        // 키스토어가 있을 때만 만들어요. 없으면 release는 서명 없이 나와요(아래 경고)
        if (hasReleaseSigning) {
            create("release") {
                storeFile = keystoreFile
                storePassword = localProps.getProperty("LOLOG_KEYSTORE_PASSWORD")
                keyAlias = localProps.getProperty("LOLOG_KEY_ALIAS")
                keyPassword = localProps.getProperty("LOLOG_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        // 평문(HTTP) 통신은 debug에서만 허용해요. 로컬 서버(http://localhost:8080, 에뮬레이터 10.0.2.2) 시험용이에요.
        // release는 HTTPS만 써요(deployment.md 6장). 매니페스트의 usesCleartextTraffic 값으로 들어가요.
        debug {
            manifestPlaceholders["usesCleartext"] = "true"
            // true: 앱 안의 목업 데이터로 동작 (백엔드 없이 에뮬레이터에서 바로 실행)
            // false: LOLOG_BASE_URL의 실제 서버(Nginx)로 요청
            buildConfigField("boolean", "USE_MOCK", "$lologUseMock")
            // 기본값: 에뮬레이터에서 내 PC의 localhost는 10.0.2.2
            buildConfigField("String", "LOLOG_BASE_URL", "\"$lologBaseUrl\"")
            // 로그인 전 시험용 토큰. 비어 있으면 안 씀
            buildConfigField("String", "LOLOG_TEST_TOKEN", "\"$lologTestToken\"")
        }
        release {
            manifestPlaceholders["usesCleartext"] = "false"
            // 배포 앱은 늘 실제 서버·실제 녹음이고, 시험용 토큰을 넣지 않아요
            buildConfigField("boolean", "USE_MOCK", "false")
            buildConfigField("String", "LOLOG_BASE_URL", "\"$lologReleaseBaseUrl\"")
            buildConfigField("String", "LOLOG_TEST_TOKEN", "\"\"")
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
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

// release 빌드를 할 때만 확인해요
gradle.taskGraph.whenReady {
    if (allTasks.none { it.name.contains("Release") }) return@whenReady
    // release는 평문 통신을 막아서 http:// 서버에는 붙지 못해요. 쓸 수 없는 APK가 나오니 빌드를 멈춰요
    if (!lologReleaseBaseUrl.startsWith("https://")) {
        throw GradleException("release 서버 주소는 https여야 해요($lologReleaseBaseUrl). LOLOG_RELEASE_BASE_URL을 확인해 주세요")
    }
    if (!hasReleaseSigning) {
        logger.warn("경고: 키스토어가 없어서 release APK가 서명 없이 나와요. 폰에 설치하려면 local.properties에 LOLOG_KEYSTORE_FILE 등을 적어 주세요(README)")
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
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)
    implementation(libs.naver.oauth)
    implementation(libs.mlkit.face.detection)
    debugImplementation(libs.androidx.ui.tooling)
    testImplementation(libs.junit)
}
