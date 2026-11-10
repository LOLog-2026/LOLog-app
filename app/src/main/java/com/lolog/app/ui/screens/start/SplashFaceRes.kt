package com.lolog.app.ui.screens.start

import com.lolog.app.R

/**
 * 첫 화면 3D 얼굴 그림 표.
 * 그림: res/drawable-nodpi/splash_face_{종류}_{입모양 0/1}_0.webp (정면 12장)
 * 11/10 다듬음: 고개 돌리기를 없애 정면 그림만 써요(80장 → 12장).
 * 원본(3D로 다시 그리기): /mnt/project-files/mockups/3D얼굴-그림/face-3d-원본.html
 */
internal object SplashFaceRes {
    private val map: Map<String, Int> = mapOf(
        "cool_0" to R.drawable.splash_face_cool_0_0,
        "cool_1" to R.drawable.splash_face_cool_1_0,
        "joy_0" to R.drawable.splash_face_joy_0_0,
        "joy_1" to R.drawable.splash_face_joy_1_0,
        "laugh_0" to R.drawable.splash_face_laugh_0_0,
        "laugh_1" to R.drawable.splash_face_laugh_1_0,
        "love_0" to R.drawable.splash_face_love_0_0,
        "love_1" to R.drawable.splash_face_love_1_0,
        "tongue_0" to R.drawable.splash_face_tongue_0_0,
        "tongue_1" to R.drawable.splash_face_tongue_1_0,
        "wow_0" to R.drawable.splash_face_wow_0_0,
        "wow_1" to R.drawable.splash_face_wow_1_0,
    )

    /** 종류·입모양(0/1)의 정면 그림. 없으면 입 다문 그림 */
    fun get(kind: String, mouth: Int): Int = map["${kind}_$mouth"] ?: map["${kind}_0"]!!
}
