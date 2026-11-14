package com.lolog.app.ui

import com.lolog.app.ui.screens.settings.OSS_ITEMS
import com.lolog.app.ui.screens.settings.SERVER_OSS_ITEMS
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 오픈소스 라이선스 화면(#124)이 가리키는 원문 파일이 assets에 모두 있는지 본다. */
class OpenSourceLicensesTest {

    @Test
    fun 원문_파일이_모두_있다() {
        (OSS_ITEMS + SERVER_OSS_ITEMS).mapNotNull { it.asset }.distinct().forEach { name ->
            val f = File("src/main/assets/licenses/$name")
            assertTrue("$name 이 없어요", f.isFile && f.length() > 0)
        }
    }

    @Test
    fun SenseVoice는_만든_곳과_모델_이름을_적는다() {
        // FunASR Model License 1.1 2.2조: 출처·만든 곳·모델 이름 표기
        val sv = OSS_ITEMS.first { it.name.startsWith("SenseVoice") }
        assertTrue(sv.author.contains("FunAudioLLM") && sv.author.contains("Alibaba"))
        assertTrue(sv.license.contains("FunASR Model License"))
    }

    @Test
    fun 앱에_넣은_모델과_엔진이_목록에_있다() {
        val names = OSS_ITEMS.joinToString { it.name }
        listOf("SenseVoice", "sherpa-onnx", "ONNX Runtime", "Silero VAD", "Pretendard", "Nanum Pen Script").forEach {
            assertTrue("$it 이 목록에 없어요", names.contains(it))
        }
    }

    @Test
    fun 서버_AI_모델과_자료를_적는다() {
        // #139: 앱에 나오는 그림·글·목소리를 만드는 서버 모델(LOLog-ai develop 기준)
        val names = SERVER_OSS_ITEMS.joinToString { it.name }
        listOf("FLUX.2 [klein] 4B", "Qwen3.5-4B", "Qwen3-TTS", "anime_face_detection", "Jua", "Freesound").forEach {
            assertTrue("$it 이 서버 목록에 없어요", names.contains(it))
        }
        // 앱 목록과 이름이 겹치지 않아야 화면 key가 안 부딪혀요
        assertTrue((OSS_ITEMS.map { it.name } intersect SERVER_OSS_ITEMS.map { it.name }.toSet()).isEmpty())
    }
}
