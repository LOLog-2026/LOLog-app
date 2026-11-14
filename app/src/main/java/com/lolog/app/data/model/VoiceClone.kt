package com.lolog.app.data.model

/*
 * 내 목소리(FR-CHAR-05). 녹음 하나를 올리면 우리 서버(Jetson)가 내 목소리를 만들어 쇼츠에서 내 캐릭터가 내 목소리로 말해요.
 * 기준: LOLog-docs PR #70(병합 전) spec/characters.md "내 목소리". 서버가 아직 없어 목업만 동작해요.
 * 푸시 VOICE_CLONE_READY·VOICE_CLONE_FAILED는 이름만 정해졌고, 받는 코드는 아직 없어요.
 */

/** PROCESSING 만드는 중 → READY 다 됨 / FAILED 실패. 만든 적이 없으면 VoiceClone 자체가 null */
enum class VoiceCloneStatus { PROCESSING, READY, FAILED }

/**
 * 프로필 캐릭터 응답의 voiceClone. previewUrl은 READY일 때 미리 듣기 주소,
 * failReason은 FAILED일 때 까닭(앱은 받아도 화면에 쓰지 않아요), shareWithFriends는 친구 쇼츠 동의(선택)
 */
data class VoiceClone(
    val status: VoiceCloneStatus,
    val previewUrl: String? = null,
    val failReason: String? = null,
    val shareWithFriends: Boolean = false,
)

/** 목소리 고르기에서 "내 목소리"를 고를 때 voice 값(명세 #70) */
const val MY_VOICE_ID = "self"

/** 내 목소리를 만들 때 읽는 문장 5개(시안 6). 임시: 앱에 두었고 팀과 정하면 바꿔요 */
val VOICE_CLONE_SENTENCES = listOf(
    "오늘 저녁엔 친구들이랑 떡볶이 먹으러 가기로 했어",
    "어제 그 영화 진짜 웃기지 않았어?",
    "비 오는 날엔 창밖 보면서 노래 듣는 게 제일 좋아",
    "주말에 어디 갈지 아직 못 정했어",
    "고마워, 덕분에 오늘 정말 즐거웠어",
)
