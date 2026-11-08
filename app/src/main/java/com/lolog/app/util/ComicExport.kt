package com.lolog.app.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.lolog.app.data.remote.ApiClient
import com.lolog.app.ui.components.ComicBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 14 저장·공유: GET /api/comics/{id}의 imageUrl 이미지를 받아 갤러리에 저장하거나 공유해요.
 * 목업 주소(mock://)면 대사로 그린 임시 그림을 써요.
 */
object ComicExport {

    /**
     * @param freshUrl 서명 주소가 만료(403)됐을 때 GET /api/comics/{id}로 새 주소를 받아 와요(명세 imageUrl). 한 번만 다시 받아요
     */
    suspend fun pngBytes(
        imageUrl: String,
        mockLines: List<String>,
        network: ApiClient?,
        freshUrl: suspend () -> String?,
    ): ByteArray? = withContext(Dispatchers.IO) {
        if (imageUrl.startsWith("mock://") || network == null) {
            val out = ByteArrayOutputStream()
            ComicBitmap.render(mockLines).compress(Bitmap.CompressFormat.PNG, 100, out)
            return@withContext out.toByteArray()
        }
        when (val first = network.download(imageUrl)) {
            is ApiClient.Download.Ok -> first.bytes
            ApiClient.Download.Failed -> null
            ApiClient.Download.Expired -> {
                val again = freshUrl()?.let { network.download(it) }
                (again as? ApiClient.Download.Ok)?.bytes
            }
        }
    }

    /** 갤러리(Pictures/LOLog)에 저장 */
    suspend fun saveToGallery(context: Context, bytes: ByteArray, name: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/LOLog")
                }
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@runCatching false
                resolver.openOutputStream(uri)?.use { it.write(bytes) }
                true
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "LOLog").apply { mkdirs() }
                File(dir, "$name.png").writeBytes(bytes)
                true
            }
        }.getOrDefault(false)
    }

    /** 공유용 임시 파일 → content:// 주소 */
    suspend fun shareUri(context: Context, bytes: ByteArray, name: String): Uri = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, "$name.png").apply { writeBytes(bytes) }
        FileProvider.getUriForFile(context, context.packageName + ".files", file)
    }

    /** 특정 앱(카카오톡·인스타그램)으로 보내기. 패키지가 없으면 시스템 공유 시트 */
    fun share(context: Context, uri: Uri, targetPackage: String? = null) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (targetPackage != null) {
            val direct = Intent(send).setPackage(targetPackage)
            if (direct.resolveActivity(context.packageManager) != null) {
                context.startActivity(direct); return
            }
        }
        context.startActivity(Intent.createChooser(send, "만화 공유").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    const val KAKAO = "com.kakao.talk"
    const val INSTAGRAM = "com.instagram.android"
}
