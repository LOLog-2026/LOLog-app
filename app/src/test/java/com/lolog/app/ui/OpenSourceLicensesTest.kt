package com.lolog.app.ui

import com.lolog.app.ui.screens.settings.OSS_ITEMS
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 오픈소스 라이선스 화면(#124)이 가리키는 원문 파일이 assets에 모두 있는지 본다. */
class OpenSourceLicensesTest {

    @Test
    fun 원문_파일이_모두_있다() {
        OSS_ITEMS.mapNotNull { it.asset }.distinct().forEach { name ->
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
}
