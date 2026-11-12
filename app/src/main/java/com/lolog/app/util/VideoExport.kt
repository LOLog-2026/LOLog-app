package com.lolog.app.util

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.lolog.app.data.remote.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 쇼츠 폰에 저장(LOLog-docs spec/video.md: 방에 올리기·외부 공유는 이번 범위가 아니고, 공유는 폰에 저장한 파일로 해요).
 * GET /api/videos/{id}의 videoUrl(mp4, 10분 서명 주소)을 받아 동영상(Movies/LOLog)에 넣어요.
 * 주소가 만료(403)됐으면 새 주소를 한 번 받아 다시 받아요(만화 저장과 같은 방식).
 */
object VideoExport {

    enum class Result { SAVED, DOWNLOAD_FAILED, SAVE_FAILED, MOCK }

    suspend fun saveToMovies(
        context: Context,
        videoUrl: String,
        name: String,
        network: ApiClient?,
        freshUrl: suspend () -> String?,
    ): Result {
        // 목업 주소는 실제 파일이 없어요
        if (videoUrl.startsWith("mock://") || network == null) return Result.MOCK
        val bytes = withContext(Dispatchers.IO) {
            ComicExport.fetchPng(videoUrl, cached = { null }, download = network::download, freshUrl = freshUrl)
        } ?: return Result.DOWNLOAD_FAILED
        return if (write(context, bytes, name)) Result.SAVED else Result.SAVE_FAILED
    }

    private suspend fun write(context: Context, bytes: ByteArray, name: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, "$name.mp4")
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/LOLog")
                }
                val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return@runCatching false
                resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return@runCatching false
                true
            } else {
                // 안드로이드 9 이하는 WRITE_EXTERNAL_STORAGE(매니페스트 maxSdkVersion 28)로 바로 써요
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "LOLog").apply { mkdirs() }
                File(dir, "$name.mp4").writeBytes(bytes)
                true
            }
        }.getOrDefault(false)
    }

    /** 결과 띠 문구 */
    fun message(r: Result): String = when (r) {
        Result.SAVED -> "동영상에 저장했어요"
        Result.DOWNLOAD_FAILED -> "영상을 받지 못했어요. 인터넷 연결을 확인해 주세요"
        Result.SAVE_FAILED -> "저장하지 못했어요"
        Result.MOCK -> "목업에서는 저장할 수 없어요"
    }
}
