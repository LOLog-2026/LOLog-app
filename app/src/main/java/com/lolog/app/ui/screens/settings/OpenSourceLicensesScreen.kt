package com.lolog.app.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lolog.app.ui.components.LologTopBar
import com.lolog.app.ui.theme.LologColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 앱에 들어간 오픈소스 하나.
 * @param asset assets/licenses/ 안의 라이선스 원문 파일. null이면 원문 대신 [url]의 약관을 따라요(Google 서비스)
 */
internal data class OssItem(
    val name: String,
    val author: String,
    val use: String,
    val license: String,
    val url: String,
    val asset: String?,
)

/**
 * 오픈소스 라이선스 목록. APK를 소개 사이트에서 누구나 받으니 출처·고지 조건을 지켜야 해요(#124).
 * SenseVoice는 FunASR Model License 1.1 2.2조에 따라 만든 곳과 모델 이름을 그대로 적어요.
 * 라이브러리를 더하거나 빼면 이 목록도 같이 고쳐요.
 */
internal val OSS_ITEMS = listOf(
    OssItem(
        "SenseVoice-Small", "FunAudioLLM (Alibaba Group)",
        "폰 안 음성 인식 모델. sherpa-onnx가 ONNX(int8)로 바꾼 판을 써요",
        "FunASR Model License 1.1", "https://huggingface.co/FunAudioLLM/SenseVoiceSmall", "funasr-model-license-1.1.txt",
    ),
    OssItem("sherpa-onnx", "k2-fsa", "음성 인식 실행", "Apache License 2.0", "https://github.com/k2-fsa/sherpa-onnx", "apache-2.0.txt"),
    OssItem("ONNX Runtime", "Microsoft Corporation", "모델 실행", "MIT License", "https://github.com/microsoft/onnxruntime", "mit-onnxruntime.txt"),
    OssItem("Silero VAD", "Silero Team", "말소리 구간 찾기", "MIT License", "https://github.com/snakers4/silero-vad", "mit-silero-vad.txt"),
    OssItem("Pretendard", "Kil Hyung-jin", "글꼴", "SIL Open Font License 1.1", "https://github.com/orioncactus/pretendard", "ofl-pretendard.txt"),
    OssItem("Nanum Pen Script", "NAVER (NHN Corporation)", "손글씨 글꼴", "SIL Open Font License 1.1", "https://hangeul.naver.com", "ofl-nanum-pen-script.txt"),
    OssItem("AndroidX · Jetpack Compose", "Google", "화면과 앱 구조", "Apache License 2.0", "https://developer.android.com/jetpack", "apache-2.0.txt"),
    OssItem("Kotlin · kotlinx.coroutines · kotlinx.serialization", "JetBrains", "언어와 기본 라이브러리", "Apache License 2.0", "https://kotlinlang.org", "apache-2.0.txt"),
    OssItem("Retrofit · OkHttp", "Square", "서버 통신", "Apache License 2.0", "https://square.github.io/retrofit", "apache-2.0.txt"),
    OssItem("Coil", "Coil Contributors", "그림 불러오기", "Apache License 2.0", "https://coil-kt.github.io/coil", "apache-2.0.txt"),
    OssItem("네이버 아이디로 로그인 SDK", "NAVER Corp.", "네이버 로그인", "Apache License 2.0", "https://github.com/naver/naveridlogin-sdk-android", "apache-2.0.txt"),
    OssItem("Firebase Cloud Messaging", "Google", "푸시 알림", "Apache License 2.0", "https://github.com/firebase/firebase-android-sdk", "apache-2.0.txt"),
    OssItem(
        "Google Play 서비스 · Sign in with Google · ML Kit 얼굴 인식", "Google",
        "구글 로그인, 프로필 사진 얼굴 확인", "Google APIs 서비스 약관", "https://developers.google.com/terms", null,
    ),
)

/**
 * 서버에서 그림·글·목소리를 만드는 AI 모델과 자료(#139). 앱 파일에는 들어 있지 않지만, 앱에 나오는 결과물을 만드는 데 써서 출처를 적어요.
 * 모델을 바꾸면 이 목록도 같이 고쳐요(LOLog-ai worker, LOLog-infra jetson 설정).
 */
internal val SERVER_OSS_ITEMS = listOf(
    OssItem(
        "FLUX.2 [klein] 4B", "Black Forest Labs",
        "4컷만화, 프로필 캐릭터, 쇼츠 장면, 기본 화자 그림", "Apache License 2.0",
        "https://huggingface.co/black-forest-labs/FLUX.2-klein-4B", "apache-2.0.txt",
    ),
    OssItem(
        "Qwen3.5-4B", "Alibaba Qwen",
        "대화에서 이야기 찾기, 만화·쇼츠 콘티, 글로 만든 에피소드", "Apache License 2.0",
        "https://huggingface.co/Qwen/Qwen3.5-4B", "apache-2.0.txt",
    ),
    OssItem(
        "Qwen3-TTS 0.6B Base · 1.7B VoiceDesign", "Alibaba Qwen",
        "쇼츠 대사와 내 목소리, 기본 화자 목소리", "Apache License 2.0",
        "https://huggingface.co/Qwen/Qwen3-TTS-12Hz-0.6B-Base", "apache-2.0.txt",
    ),
    OssItem(
        "anime_face_detection", "DeepGHS",
        "만화 말풍선 자리 찾기", "MIT License",
        "https://huggingface.co/deepghs/anime_face_detection", "mit-anime-face-detection.txt",
    ),
    OssItem("Jua", "The Jua Project Authors", "만화 말풍선·쇼츠 자막 글꼴", "SIL Open Font License 1.1", "https://fonts.google.com/specimen/Jua", "ofl-jua.txt"),
    OssItem("Freesound 효과음·배경음", "Freesound 올린 사람들", "쇼츠 효과음·배경음(CC0만 씀)", "CC0 1.0 Universal", "https://freesound.org", "cc0-1.0.txt"),
)

@Composable
fun OpenSourceLicensesScreen(onBack: () -> Unit) {
    var open by remember { mutableStateOf<OssItem?>(null) }
    val item = open
    if (item != null) {
        BackHandler { open = null }
        LicenseText(item, onBack = { open = null })
        return
    }
    Scaffold(
        containerColor = LologColors.Paper,
        topBar = { LologTopBar("오픈소스 라이선스", onBack) },
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        ) {
            item {
                Text(
                    "LOLog는 아래 오픈소스를 써요. 누르면 라이선스 원문을 볼 수 있어요.",
                    fontSize = 13.sp, color = LologColors.Muted, lineHeight = 19.sp,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            items(OSS_ITEMS, key = { it.name }) { o -> OssRow(o) { open = o } }
            item {
                Text(
                    "서버 AI 모델", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = LologColors.Ink,
                    modifier = Modifier.padding(top = 24.dp, bottom = 4.dp),
                )
                Text(
                    "앱에는 들어 있지 않고, LOLog 서버에서 만화·쇼츠·목소리를 만들 때 써요. 대화는 외부 회사로 보내지 않아요.",
                    fontSize = 13.sp, color = LologColors.Muted, lineHeight = 19.sp,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            items(SERVER_OSS_ITEMS, key = { "server-" + it.name }) { o -> OssRow(o) { open = o } }
        }
    }
}

/** 목록 한 줄: 이름 · 만든 곳과 쓰는 곳 · 라이선스. 누르면 원문 */
@Composable
private fun OssRow(o: OssItem, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(o.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = LologColors.Ink, modifier = Modifier.weight(1f))
            Text("›", fontSize = 15.sp, color = LologColors.Muted)
        }
        Text("${o.author} · ${o.use}", fontSize = 12.5.sp, color = LologColors.Muted, lineHeight = 18.sp)
        Text(o.license, fontSize = 12.5.sp, color = LologColors.Accent, fontWeight = FontWeight.SemiBold)
    }
    HorizontalDivider(color = LologColors.Line)
}

/** 라이선스 원문. 원문 파일이 없는 항목(Google 서비스)은 약관 주소만 보여요 */
@Composable
private fun LicenseText(item: OssItem, onBack: () -> Unit) {
    val context = LocalContext.current
    var text by remember(item) { mutableStateOf<String?>(null) }
    LaunchedEffect(item) {
        text = item.asset?.let { name ->
            withContext(Dispatchers.IO) {
                runCatching { context.assets.open("licenses/$name").bufferedReader().use { it.readText() } }.getOrNull()
            }
        }
    }
    Scaffold(
        containerColor = LologColors.Paper,
        topBar = { LologTopBar(item.name, onBack) },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("${item.author} · ${item.license}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = LologColors.Ink)
            SelectionContainer { Text(item.url, fontSize = 12.5.sp, color = LologColors.Muted) }
            Spacer(Modifier.height(6.dp))
            when {
                item.asset == null -> Text(
                    "이 서비스는 위 주소의 약관을 따라요.", fontSize = 13.sp, color = LologColors.Ink, lineHeight = 20.sp,
                )
                text != null -> SelectionContainer {
                    Text(text!!, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, color = LologColors.Ink, lineHeight = 17.sp)
                }
            }
        }
    }
}
