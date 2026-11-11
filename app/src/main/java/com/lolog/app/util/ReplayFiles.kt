package com.lolog.app.util

import android.content.Context
import com.lolog.app.data.remote.ApiClient
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 순간 다시 듣기의 임시 파일(replay.md 3장).
 * 카드 구간 음성을 통째로 받아 cacheDir/replay/에 두고 재생해요. 카드 화면을 나갈 때, 로그아웃·탈퇴 때 지워요.
 */
object ReplayFiles {
    private fun dir(context: Context) = File(context.cacheDir, "replay")

    /** 받아 둔 음성을 모두 지워요. 로그아웃·탈퇴, 앱을 켤 때(지난번에 못 지운 것) 불러요 */
    fun clear(context: Context) {
        dir(context).deleteRecursively()
    }

    sealed interface Result {
        class Ok(val file: File) : Result
        data object Failed : Result
    }

    /**
     * url에서 받아 임시 파일로 둬요. 서명 주소가 만료(403)됐으면 freshUrl로 새 주소를 받아 한 번만 다시 받아요.
     * 목업 주소(mock://)는 들을 수 있는 시험음을 만들어요.
     */
    suspend fun download(
        context: Context,
        cardId: Long,
        url: String,
        durationMs: Long,
        network: ApiClient?,
        freshUrl: suspend () -> String?,
    ): Result = withContext(Dispatchers.IO) {
        val target = File(dir(context).apply { mkdirs() }, "card-$cardId.aac")
        if (url.startsWith("mock://") || network == null) {
            val wav = File(target.parentFile, "card-$cardId.wav")
            writeTestTone(wav, durationMs)
            return@withContext Result.Ok(wav)
        }
        val bytes = when (val first = network.download(url)) {
            is ApiClient.Download.Ok -> first.bytes
            ApiClient.Download.Failed -> null
            ApiClient.Download.Expired -> (freshUrl()?.let { network.download(it) } as? ApiClient.Download.Ok)?.bytes
        } ?: return@withContext Result.Failed
        target.writeBytes(bytes)
        Result.Ok(target)
    }

    /** 목업용: 0.5초마다 짧게 울리는 16kHz 모노 WAV. 재생 바와 위치 옮기기를 귀로 확인할 수 있어요 */
    private fun writeTestTone(file: File, durationMs: Long) {
        val rate = 16_000
        val samples = (durationMs * rate / 1000).toInt()
        val data = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until samples) {
            val t = i.toDouble() / rate
            // 5초마다 음 높이를 올려서 어디쯤인지 들리게 해요
            val freq = 440.0 + 110.0 * ((t / 5).toInt() % 4)
            val on = (t % 0.5) < 0.15
            data.putShort(if (on) (sin(2 * PI * freq * t) * 6000).toInt().toShort() else 0)
        }
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + samples * 2); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(samples * 2)
        }
        FileOutputStream(file).use { it.write(header.array()); it.write(data.array()) }
    }
}
