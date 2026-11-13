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
 * 쇼츠 내보내기(LOLog-docs video.md 9장): 폰에 저장, 카카오톡·인스타그램·다른 앱으로 공유.
 * GET /api/videos/{id}의 videoUrl(mp4, 10분 서명 주소)을 받아 동영상(Movies/LOLog)에 넣거나 안드로이드 공유(ACTION_SEND, video/mp4)로 넘겨요.
 * 카카오톡·인스타그램은 만화 공유처럼 그 앱을 고른 공유(setPackage)이고 카카오 SDK는 안 써요.
 * 공유한 영상은 갤러리에 남기지 않고 공유용 임시 파일(cacheDir/shared)로만 둬요(정함 11-12).
 * 주소가 만료(403)됐으면 새 주소를 한 번 받아 다시 받아요(만화 저장과 같은 방식).
 */
object VideoExport {

    /** NO_APP: 고른 앱(카카오톡·인스타그램)이 폰에 없어요 */
    enum class Result { SAVED, SHARED, NO_APP, DOWNLOAD_FAILED, SAVE_FAILED, MOCK }

    /** 공유할 곳. [pkg]가 null이면 안드로이드 공유 창(다른 앱으로 공유) */
    enum class Target(val pkg: String?, val appName: String) {
        KAKAO("com.kakao.talk", "카카오톡"),
        INSTAGRAM("com.instagram.android", "인스타그램"),
        OTHER(null, ""),
    }

    private const val SHARE_PREFIX = "lolog_shorts_"

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
     * 공유 화면으로 넘기기. 인스타그램은 공유 화면에서 피드·스토리·릴스를 사용자가 골라요.
     * 고른 앱이 없으면 영상을 받지 않고 바로 NO_APP이에요.
     * 받은 파일은 cacheDir/shared/lolog_shorts_{id}.mp4에 두고, 예전에 공유한 다른 쇼츠 파일은 이때 지워요
     * (보낸 뒤 바로 지우면 받는 앱이 아직 못 읽었을 수 있어서 다음 공유 때 지워요, 초안).
     * 받는 중에 코루틴이 취소되면 공유 화면을 열지 않아요.
     */
    suspend fun share(
        context: Context,
        target: Target,
        videoUrl: String,
        videoId: Long,
        network: ApiClient?,
        freshUrl: suspend () -> String?,
    ): Result {
        val send = Intent(Intent.ACTION_SEND).setType("video/mp4").apply { target.pkg?.let { setPackage(it) } }
        return shareFlow(
            videoUrl = videoUrl,
            hasApp = { target.pkg == null || send.resolveActivity(context.packageManager) != null },
            download = network?.let { it::download },
            freshUrl = freshUrl,
            open = { bytes -> open(context, send, target, bytes, "$SHARE_PREFIX$videoId") },
        )
    }

    /** 앱 확인 → (목업이면 MOCK) → 받기(403이면 새 주소로 한 번 더) → 열기. 테스트에서 가짜를 넣으려고 나눠 뒀어요 */
    internal suspend fun shareFlow(
        videoUrl: String,
        hasApp: () -> Boolean,
        download: ((String) -> ApiClient.Download)?,
        freshUrl: suspend () -> String?,
        open: suspend (ByteArray) -> Result,
    ): Result {
        if (!hasApp()) return Result.NO_APP
        // 목업 주소는 실제 파일이 없어요
        if (videoUrl.startsWith("mock://") || download == null) return Result.MOCK
        val bytes = withContext(Dispatchers.IO) {
            ComicExport.fetchPng(videoUrl, cached = { null }, download = download, freshUrl = freshUrl)
        } ?: return Result.DOWNLOAD_FAILED
        // [취소]로 코루틴이 취소되면 withContext가 끝날 때 멈춰서 여기까지 오지 않아요
        return open(bytes)
    }

    /** 공유용 파일을 쓰고 content:// 주소로 고른 앱(없으면 공유 창)을 열어요 */
    private suspend fun open(context: Context, send: Intent, target: Target, bytes: ByteArray, name: String): Result {
        val uri = withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(context.cacheDir, "shared").apply { mkdirs() }
                staleShares(dir.listFiles()?.map { it.name }.orEmpty(), keep = "$name.mp4").forEach { File(dir, it).delete() }
                val file = File(dir, "$name.mp4").apply { writeBytes(bytes) }
                FileProvider.getUriForFile(context, context.packageName + ".files", file)
            }.getOrNull()
        } ?: return Result.SAVE_FAILED
        val intent = Intent(send).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return try {
            context.startActivity(
                if (target.pkg == null) Intent.createChooser(intent, "쇼츠 공유").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) else intent,
            )
            Result.SHARED
        } catch (e: ActivityNotFoundException) {
            // 받는 사이 그 앱을 지웠을 때
            Result.NO_APP
        }
    }

    /** cacheDir/shared에서 지울 예전 쇼츠 공유 파일(만화 공유 파일은 그대로 둬요) */
    internal fun staleShares(names: List<String>, keep: String): List<String> =
        names.filter { it.startsWith(SHARE_PREFIX) && it.endsWith(".mp4") && it != keep }

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
     * SHARED: 받는 앱에서 보냈는지는 알 수 없어서 띄우지 않아요. NO_APP: 띠 대신 창으로 알려요
     * @param sharing 공유 결과면 true(목업 문구가 달라요)
     */
    fun message(r: Result, sharing: Boolean = false): String? = when (r) {
        Result.SAVED -> "동영상에 저장했어요"
        Result.SHARED, Result.NO_APP -> null
        Result.DOWNLOAD_FAILED -> "영상을 받지 못했어요. 인터넷 연결을 확인해 주세요"
        Result.SAVE_FAILED -> "저장하지 못했어요"
        Result.MOCK -> if (sharing) "목업에서는 공유할 수 없어요" else "목업에서는 저장할 수 없어요"
    }
}
