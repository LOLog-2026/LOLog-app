package com.lolog.app.ui.screens.start

import com.lolog.app.R

/**
 * 첫 화면 3D 얼굴 그림 표 (자동으로 만든 파일).
 * 그림: res/drawable-nodpi/splash_face_{종류}_{입모양 0/1}_{각도}.webp
 * 각도: 고개 돌리기 -30·-15·0·15·30, 선글라스(cool)는 한 바퀴 0~330(30°씩).
 * 원본(3D로 다시 그리기): /mnt/project-files/mockups/3D얼굴-그림/face-3d-원본.html
 */
internal object SplashFaceRes {
    private val map: Map<String, Int> = mapOf(
        "cool_0_0" to R.drawable.splash_face_cool_0_0,
        "cool_0_120" to R.drawable.splash_face_cool_0_120,
        "cool_0_15" to R.drawable.splash_face_cool_0_15,
        "cool_0_150" to R.drawable.splash_face_cool_0_150,
        "cool_0_180" to R.drawable.splash_face_cool_0_180,
        "cool_0_210" to R.drawable.splash_face_cool_0_210,
        "cool_0_240" to R.drawable.splash_face_cool_0_240,
        "cool_0_270" to R.drawable.splash_face_cool_0_270,
        "cool_0_30" to R.drawable.splash_face_cool_0_30,
        "cool_0_300" to R.drawable.splash_face_cool_0_300,
        "cool_0_330" to R.drawable.splash_face_cool_0_330,
        "cool_0_60" to R.drawable.splash_face_cool_0_60,
        "cool_0_90" to R.drawable.splash_face_cool_0_90,
        "cool_0_-15" to R.drawable.splash_face_cool_0_m15,
        "cool_0_-30" to R.drawable.splash_face_cool_0_m30,
        "cool_1_0" to R.drawable.splash_face_cool_1_0,
        "cool_1_120" to R.drawable.splash_face_cool_1_120,
        "cool_1_15" to R.drawable.splash_face_cool_1_15,
        "cool_1_150" to R.drawable.splash_face_cool_1_150,
        "cool_1_180" to R.drawable.splash_face_cool_1_180,
        "cool_1_210" to R.drawable.splash_face_cool_1_210,
        "cool_1_240" to R.drawable.splash_face_cool_1_240,
        "cool_1_270" to R.drawable.splash_face_cool_1_270,
        "cool_1_30" to R.drawable.splash_face_cool_1_30,
        "cool_1_300" to R.drawable.splash_face_cool_1_300,
        "cool_1_330" to R.drawable.splash_face_cool_1_330,
        "cool_1_60" to R.drawable.splash_face_cool_1_60,
        "cool_1_90" to R.drawable.splash_face_cool_1_90,
        "cool_1_-15" to R.drawable.splash_face_cool_1_m15,
        "cool_1_-30" to R.drawable.splash_face_cool_1_m30,
        "joy_0_0" to R.drawable.splash_face_joy_0_0,
        "joy_0_15" to R.drawable.splash_face_joy_0_15,
        "joy_0_30" to R.drawable.splash_face_joy_0_30,
        "joy_0_-15" to R.drawable.splash_face_joy_0_m15,
        "joy_0_-30" to R.drawable.splash_face_joy_0_m30,
        "joy_1_0" to R.drawable.splash_face_joy_1_0,
        "joy_1_15" to R.drawable.splash_face_joy_1_15,
        "joy_1_30" to R.drawable.splash_face_joy_1_30,
        "joy_1_-15" to R.drawable.splash_face_joy_1_m15,
        "joy_1_-30" to R.drawable.splash_face_joy_1_m30,
        "laugh_0_0" to R.drawable.splash_face_laugh_0_0,
        "laugh_0_15" to R.drawable.splash_face_laugh_0_15,
        "laugh_0_30" to R.drawable.splash_face_laugh_0_30,
        "laugh_0_-15" to R.drawable.splash_face_laugh_0_m15,
        "laugh_0_-30" to R.drawable.splash_face_laugh_0_m30,
        "laugh_1_0" to R.drawable.splash_face_laugh_1_0,
        "laugh_1_15" to R.drawable.splash_face_laugh_1_15,
        "laugh_1_30" to R.drawable.splash_face_laugh_1_30,
        "laugh_1_-15" to R.drawable.splash_face_laugh_1_m15,
        "laugh_1_-30" to R.drawable.splash_face_laugh_1_m30,
        "love_0_0" to R.drawable.splash_face_love_0_0,
        "love_0_15" to R.drawable.splash_face_love_0_15,
        "love_0_30" to R.drawable.splash_face_love_0_30,
        "love_0_-15" to R.drawable.splash_face_love_0_m15,
        "love_0_-30" to R.drawable.splash_face_love_0_m30,
        "love_1_0" to R.drawable.splash_face_love_1_0,
        "love_1_15" to R.drawable.splash_face_love_1_15,
        "love_1_30" to R.drawable.splash_face_love_1_30,
        "love_1_-15" to R.drawable.splash_face_love_1_m15,
        "love_1_-30" to R.drawable.splash_face_love_1_m30,
        "tongue_0_0" to R.drawable.splash_face_tongue_0_0,
        "tongue_0_15" to R.drawable.splash_face_tongue_0_15,
        "tongue_0_30" to R.drawable.splash_face_tongue_0_30,
        "tongue_0_-15" to R.drawable.splash_face_tongue_0_m15,
        "tongue_0_-30" to R.drawable.splash_face_tongue_0_m30,
        "tongue_1_0" to R.drawable.splash_face_tongue_1_0,
        "tongue_1_15" to R.drawable.splash_face_tongue_1_15,
        "tongue_1_30" to R.drawable.splash_face_tongue_1_30,
        "tongue_1_-15" to R.drawable.splash_face_tongue_1_m15,
        "tongue_1_-30" to R.drawable.splash_face_tongue_1_m30,
        "wow_0_0" to R.drawable.splash_face_wow_0_0,
        "wow_0_15" to R.drawable.splash_face_wow_0_15,
        "wow_0_30" to R.drawable.splash_face_wow_0_30,
        "wow_0_-15" to R.drawable.splash_face_wow_0_m15,
        "wow_0_-30" to R.drawable.splash_face_wow_0_m30,
        "wow_1_0" to R.drawable.splash_face_wow_1_0,
        "wow_1_15" to R.drawable.splash_face_wow_1_15,
        "wow_1_30" to R.drawable.splash_face_wow_1_30,
        "wow_1_-15" to R.drawable.splash_face_wow_1_m15,
        "wow_1_-30" to R.drawable.splash_face_wow_1_m30,
    )

    /** 가장 가까운 각도의 그림. 없으면 정면 그림 */
    fun get(kind: String, mouth: Int, yaw: Int): Int {
        val angles = if (kind == "cool" && (yaw < -30 || yaw > 30)) (0 until 360 step 30).toList() else listOf(-30, -15, 0, 15, 30)
        val norm = if (angles.size == 12) ((yaw % 360) + 360) % 360 else yaw.coerceIn(-30, 30)
        val near = angles.minBy { a -> minOf(kotlin.math.abs(a - norm), 360 - kotlin.math.abs(a - norm)) }
        return map["${kind}_${mouth}_$near"] ?: map["${kind}_${mouth}_0"]!!
    }
}
