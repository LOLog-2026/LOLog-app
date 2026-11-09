# sherpa-onnx 파일 받기

`.so`와 모델은 크기가 커서 커밋하지 않아요. 앱을 빌드하기 전에 아래 파일을 넣어 주세요. 합쳐서 약 260MB이고, 받는 파일은 약 210MB예요.

| 넣을 곳 | 파일 | 받는 곳 |
| --- | --- | --- |
| `app/src/main/jniLibs/arm64-v8a/` | `libsherpa-onnx-jni.so`, `libonnxruntime.so` | [sherpa-onnx-2.0.1.aar](https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-2.0.1.aar)의 `jni/arm64-v8a/` (aar은 zip이에요) |
| `app/src/main/assets/` | `model.int8.onnx`, `tokens.txt` | [SenseVoice 2024-07-17판 int8](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2) |
| `app/src/main/assets/` | `silero_vad.onnx` | [Silero VAD](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx) |

MD5로 확인할 수 있어요.

- `libsherpa-onnx-jni.so`: `1cab450989d65a80acb8323aba7de9a8`
- `libonnxruntime.so`: `87880a7261441057c4f9059ece093feb`

`app/build.gradle.kts`에 아래가 필요해요. 모델을 압축하지 않아야 로딩이 느려지지 않아요.

```kotlin
android {
    defaultConfig { ndk { abiFilters += listOf("arm64-v8a") } }
    androidResources { noCompress += listOf("onnx") }
}
```

`OfflineRecognizer.kt` 같은 Kotlin API 파일은 sherpa-onnx 저장소 `sherpa-onnx/kotlin-api`에서 복사했어요. JNI 이름 때문에 폴더와 달리 패키지는 `com.k2fsa.sherpa.onnx` 그대로예요.
