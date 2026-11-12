package com.lolog.app.util

import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.lolog.app.data.remote.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 쇼츠 폰에 저장(LOLog-docs spec/video.md: 방에 올리기는 이번 범위가 아니고, 공유는 인스타그램 릴스만(앱이 mp4를 받아 인스타 공유 화면으로 넘김, 11/12 서현 결정)).
 * GET /api/videos/{id}의 videoUrl(mp4, 10분 서명 주소)을 받아 동영상(Movies/LOLog)에 넣거나 인스타그램으로 넘겨요.
 * 주소가 만료(403)됐으면 새 주소를 한 번 받아 다시 받아요(만화 저장과 같은 방식).
 */
object VideoExport {

    enum class Result { SAVED, SHARED, NO_INSTAGRAM, DOWNLOAD_FAILED, SAVE_FAILED, MOCK }

    const val INSTAGRAM = "com.instagram.android"

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

    /**
     * 인스타그램 공유 화면으로 넘기기. 릴스는 인스타 안에서 사용자가 골라요(릴스 편집 화면으로 바로 보내려면 메타 앱 ID가 필요해서 안 씀).
     * 인스타그램이 없으면 영상을 받지 않고 바로 NO_INSTAGRAM이에요.
     * 받은 파일은 cacheDir/shared/lolog_shorts_{id}.mp4에 두고 다음 공유 때 덮어써요.
     * 받는 중에 코루틴이 취소되면 인스타그램을 열지 않아요.
     */
    suspend fun shareToInstagram(
        context: Context,
        videoUrl: String,
        videoId: Long,
        network: ApiClient?,
        freshUrl: suspend () -> String?,
    ): Result {
        val send = Intent(Intent.ACTION_SEND).setType("video/mp4").setPackage(INSTAGRAM)
        return shareFlow(
            videoUrl = videoUrl,
            hasInstagram = { send.resolveActivity(context.packageManager) != null },
            download = network?.let { it::download },
            freshUrl = freshUrl,
            open = { bytes -> open(context, send, bytes, "lolog_shorts_$videoId") },
        )
    }

    /** 인스타 확인 → (목업이면 MOCK) → 받기(403이면 새 주소로 한 번 더) → 열기. 테스트에서 가짜를 넣으려고 나눠 뒀어요 */
    internal suspend fun shareFlow(
        videoUrl: String,
        hasInstagram: () -> Boolean,
        download: ((String) -> ApiClient.Download)?,
        freshUrl: suspend () -> String?,
        open: suspend (ByteArray) -> Result,
    ): Result {
        if (!hasInstagram()) return Result.NO_INSTAGRAM
        // 목업 주소는 실제 파일이 없어요
        if (videoUrl.startsWith("mock://") || download == null) return Result.MOCK
        val bytes = withContext(Dispatchers.IO) {
            ComicExport.fetchPng(videoUrl, cached = { null }, download = download, freshUrl = freshUrl)
        } ?: return Result.DOWNLOAD_FAILED
        // [취소]로 코루틴이 취소되면 withContext가 끝날 때 멈춰서 여기까지 오지 않아요
        return open(bytes)
    }

    /** 공유용 파일을 쓰고 content:// 주소로 인스타그램을 열어요 */
    private suspend fun open(context: Context, send: Intent, bytes: ByteArray, name: String): Result {
        val uri = withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(context.cacheDir, "shared").apply { mkdirs() }
                val file = File(dir, "$name.mp4").apply { writeBytes(bytes) }
                FileProvider.getUriForFile(context, context.packageName + ".files", file)
            }.getOrNull()
        } ?: return Result.SAVE_FAILED
        return try {
            context.startActivity(
                Intent(send).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
            Result.SHARED
        } catch (e: ActivityNotFoundException) {
            // 받는 사이 인스타그램을 지웠을 때
            Result.NO_INSTAGRAM
        }
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

    /**
     * 결과 띠 문구. 띠를 띄우지 않으면 null이에요.
     * SHARED: 인스타에서 올렸는지는 알 수 없어서 띄우지 않아요. NO_INSTAGRAM: 띠 대신 창으로 알려요
     * @param sharing 인스타그램 공유 결과면 true(목업 문구가 달라요)
     */
    fun message(r: Result, sharing: Boolean = false): String? = when (r) {
        Result.SAVED -> "동영상에 저장했어요"
        Result.SHARED, Result.NO_INSTAGRAM -> null
        Result.DOWNLOAD_FAILED -> "영상을 받지 못했어요. 인터넷 연결을 확인해 주세요"
        Result.SAVE_FAILED -> "저장하지 못했어요"
        Result.MOCK -> if (sharing) "목업에서는 공유할 수 없어요" else "목업에서는 저장할 수 없어요"
    }
}
