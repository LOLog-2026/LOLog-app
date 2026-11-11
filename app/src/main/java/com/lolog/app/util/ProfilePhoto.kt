package com.lolog.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * 프로필 캐릭터 사진 준비(characters.md 3장).
 *  - 고른 사진을 긴 변 1600px 이하 JPEG로 바꿔요. 서버는 JPEG·PNG 10MB까지만 받고, 폰 사진(HEIC 등)도 그대로 올릴 수 있게요
 *  - 올리기 전에 폰 안에서 얼굴이 한 명만 보이는지 확인해요(ML Kit 얼굴 감지, 모델 내장). 서버와 워커는 얼굴을 따로 보지 않아요
 */
object ProfilePhoto {
    private const val MAX_SIDE = 1600
    private const val JPEG_QUALITY = 90

    sealed interface Result {
        class Ok(val jpeg: ByteArray) : Result
        /** 얼굴이 없거나 여러 명 */
        data class WrongFaces(val count: Int) : Result
        /** 사진을 읽지 못함 */
        data object Unreadable : Result
    }

    suspend fun prepare(context: Context, uri: Uri): Result = withContext(Dispatchers.IO) {
        val bitmap = runCatching { decode(context, uri) }.getOrNull() ?: return@withContext Result.Unreadable
        try {
            val faces = countFaces(bitmap)
            if (faces != 1) return@withContext Result.WrongFaces(faces)
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            Result.Ok(out.toByteArray())
        } finally {
            bitmap.recycle()
        }
    }

    // ImageDecoder(안드로이드 9 이상)는 사진의 회전 정보를 반영해요. 더 낮은 폰은 BitmapFactory로 줄여서 읽어요
    private fun decode(context: Context, uri: Uri): Bitmap? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                val (w, h) = info.size.width to info.size.height
                val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(w, h))
                decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE // ML Kit·JPEG 압축이 하드웨어 비트맵을 못 읽어요
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        }

    private suspend fun countFaces(bitmap: Bitmap): Int {
        val detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setMinFaceSize(0.1f) // 사진 폭의 10%보다 작은 뒤쪽 사람 얼굴은 세지 않아요
                .build(),
        )
        return try {
            suspendCancellableCoroutine { cont ->
                detector.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { cont.resume(it.size) }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
        } finally {
            detector.close()
        }
    }
}
