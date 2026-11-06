# lolog-app

LOLog Android 앱이에요.

## 분담

지원과 서현이 나눠 만들 때의 기준이에요. 팀에서 바꿔도 돼요. 바꾸면 이 문서도 같이 고쳐요.

### 담당

| 담당 | 맡는 것 | 폴더 |
| --- | --- | --- |
| 서현 | 앱 뼈대, 서버 통신 연결부, 화면(녹음, 분석 중, 카드 목록·상세, 만화 보기), 로그인, FCM | `ui/`, `data/remote/` |
| 지원 | 녹음 서비스(포그라운드 서비스, 마이크, STT, 문장 저장), 업로드, 녹음 중 알림, STT 엔진 선정 | `recording/`, `recording/stt/` |

두 사람이 같이 건드리는 파일은 `recording/RecordingController.kt`와 `data/remote/ApiClient.kt`뿐이에요. 이 둘은 뼈대에 먼저 넣고, 고칠 때는 서로 알려요.

### 먼저 돌아가야 하는 흐름

녹음 시작·정지 → 텍스트 업로드 → "분석 중" → 카드 목록 → 카드 골라 만화 요청 → 만화 보기

그다음에는 이 순서로 얹어요.

1. 이메일 로그인 (그 전에는 테스트 토큰 하나를 고정해서 써요)
2. 업로드 실패 시 다시 올리기, 토큰 갱신
3. FCM 푸시 (그 전에는 화면이 3초마다 상태를 물어봐요)
4. 카드 고치기, 소셜 로그인, 공유, 설정·탈퇴

### 앱 설정

| 항목 | 값 |
| --- | --- |
| 패키지 | `com.lolog.app` |
| 언어 | Kotlin |
| minSdk / compileSdk·targetSdk | 26 / 36 |
| 시험 기기 | LG LM-G900N (Android 13, API 33, arm64-v8a) |
| 화면 | Jetpack Compose + Navigation Compose |
| 비동기 | Coroutines + Flow |
| 서버 통신 | Retrofit + OkHttp + kotlinx.serialization |
| 만화 이미지 | Coil |
| DI | 쓰지 않아요. 객체는 `AppContainer` 하나에 모아요 |
| 녹음 글 저장 | 앱 파일 폴더. 업로드에 성공하면 지워요 |
| STT | sherpa-onnx. `.so`는 `jniLibs/arm64-v8a`에, 모델은 `assets`에 넣어요 |
| 서버 주소·테스트 토큰 | `local.properties`의 `LOLOG_BASE_URL`, `LOLOG_TEST_TOKEN` → BuildConfig |

```
com.lolog.app
  AppContainer.kt    객체 모음 (ApiClient, RecordingController)
  ui/                서현: 화면
  data/remote/       서현: ApiClient, API 인터페이스, DTO
  recording/         지원: 녹음 서비스, 업로드
  recording/stt/     지원: sherpa-onnx Kotlin API
```

`local.properties`, `.so`, 모델 파일, `google-services.json`은 커밋하지 않아요.

### 녹음 서비스 ↔ 화면

화면은 이 인터페이스만 알면 돼요. STT, 마이크, 업로드는 몰라도 돼요. 녹음 쪽 안을 바꿔도(엔진 교체 등) 화면 코드는 바뀌지 않아요. 뼈대에 가짜 구현(5초 녹음하는 척하고 완료)을 넣어 두면 화면부터 만들 수 있어요.

```kotlin
package com.lolog.app.recording

interface RecordingController {
    val state: StateFlow<RecordingState>
    fun start()   // 마이크·알림 권한은 화면이 미리 받아 둠
    fun stop()    // 녹음을 끝내고 마지막 묶음 업로드까지 진행
}

sealed interface RecordingState {
    data object Idle : RecordingState

    // 녹음 중. 1시간마다 올리는 중간 묶음의 상태는 partUpload에 덧붙임
    data class Recording(
        val elapsedSec: Long,
        val partUpload: PartUpload = PartUpload.None,
    ) : RecordingState

    data object Uploading : RecordingState                         // 정지 후 마지막 묶음 올리는 중
    data class Uploaded(val recordingId: Long) : RecordingState   // 서버가 준 recordingId
    data class UploadFailed(val message: String) : RecordingState // 폰에 묶음 남아 있음
}

sealed interface PartUpload {
    data object None : PartUpload
    data class Sending(val partNo: Int) : PartUpload
    data class Failed(val partNo: Int) : PartUpload   // 녹음은 계속, 다음에 다시 올림
}
```

- 화면은 `Uploaded`를 받으면 그 `recordingId`로 "분석 중" 화면으로 넘어가요. `GET /api/recordings/{id}`를 3초마다 물어 `DONE`이면 카드 목록으로 가요.
- `Uploaded`나 `UploadFailed`를 보여 준 뒤에는 다음 `start()` 때 `Idle`부터 다시 시작해요.
- 녹음 중에 묶음을 올려도 상태는 `Recording`이고 `partUpload`만 바뀌어요. 화면은 녹음 시간 옆에 "올리는 중" 또는 "올리기 실패, 다음에 다시 시도" 정도만 작게 보여 줘요.
- 화면에서 쓰는 녹음 ID는 서버가 준 `recordingId`뿐이에요. 앱이 만든 `clientRecordingId`는 녹음 서비스 안에서만 써요.

### 서버 통신

서현이 `ApiClient` 하나(Retrofit, 서버 주소, `Authorization: Bearer` 붙이기, 오류 `{code, message}` 풀기)를 만들고, 지원은 그 위에 업로드 함수 하나만 써요. 주소와 오류 코드의 기준은 [API 명세](https://github.com/LOLog-2026/LOLog-docs/blob/main/spec/api-spec.md), 요청·응답 필드의 기준은 [API DTO](https://github.com/LOLog-2026/LOLog-docs/blob/main/spec/api-dto.md)예요.

| API | 부르는 사람 |
| --- | --- |
| `POST /api/recordings` | 지원 (녹음 서비스) |
| `GET /api/recordings/{id}` | 서현 (분석 중 화면, 3초마다) |
| `GET /api/cards?cursor=&size=20` | 서현 |
| `GET /api/cards/{id}` | 서현 |
| `POST /api/cards/{id}/comics` | 서현 |
| `GET /api/comics/{id}` | 서현 (3초마다) |

시간은 ISO-8601 문자열, ID는 숫자(Long)로 맞춰요. `clientRecordingId`만 문자열이에요.

### 알림 채널

채널은 `MainApplication`(뼈대)에서 한 번에 만들고, 각자 ID만 가져다 써요. 알림 권한(`POST_NOTIFICATIONS`)은 마이크 권한과 함께 녹음 화면에서 받아요.

| 채널 ID | 설정에 보이는 이름 | 담당 | 쓰임 | 중요도 |
| --- | --- | --- | --- | --- |
| `recording` | 녹음 중 | 지원 | 녹음 중 상단 고정 알림 | 낮음 (소리 없음) |
| `recording_end` | 녹음 종료 | 지원 | 3시간이 되어 녹음을 끝냈다는 알림 | 기본 |
| `push` | 분석·만화 완료 | 서현 | FCM 푸시 | 기본 |

## 화면 쪽 실행 안내 (서현)

- 처음에는 **목업 모드**(`app/build.gradle.kts`의 `USE_MOCK=true`)라 서버 없이 모든 화면이 돌아가요. 녹음도 가짜(`data/mock/MockRecordingController`)라 마이크·STT 없이 시간만 흘러요.
- 실제 서버로 바꾸려면 `local.properties`에 `LOLOG_BASE_URL`(없으면 `http://10.0.2.2:8080/`), 필요하면 `LOLOG_TEST_TOKEN`을 적고 `USE_MOCK`을 `false`로 바꿔요.
- 실제 녹음은 지원 님 PR #3이 머지된 뒤 `AppContainer.recorder`를 `RealRecordingController`로 바꿔요(코드에 TODO).
- 백엔드와 같이 정할 것은 [`docs/백엔드-프론트-같이-정할-것.md`](docs/백엔드-프론트-같이-정할-것.md)에 모았어요.
- 글꼴 Pretendard, Nanum Pen Script는 SIL Open Font License 1.1이에요(`licenses/`).

### 목업에서 오류 화면 보기

| 하는 것 | 결과 |
| --- | --- |
| 네이버로 시작하기 | 409 ACCOUNT_EXISTS 창 |
| 회원가입에 `taken@lolog.app` | 409 ACCOUNT_EXISTS 창 |
| 비밀번호 4자 미만 로그인 | INVALID_CREDENTIALS |
| `offline@…`로 로그인 | 인터넷 끊김 |
| 설정 → Google 연결하기 | 409 SOCIAL_ALREADY_LINKED |
| 녹음 15초 안에 끝내기 | 이야기를 못 찾았어요 |
| 만드는 중인 만화가 있는 카드 고치기 | CARD_LOCKED |
| "택시 기사님의 인생 상담" 카드 | 3/3번, 버튼 잠김 (서버는 409 COMIC_LIMIT) |
