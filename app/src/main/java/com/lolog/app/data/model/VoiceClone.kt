package com.lolog.app.data.model

/*
 * 내 목소리 만들기(시안 11/13). 문장 5개를 읽어 올리면 서버가 내 목소리를 만들어 쇼츠에서 내 캐릭터가 내 목소리로 말해요.
 * 명세 없음, 목업만. 서버가 생기면 상태 값·필드 이름을 명세에 맞출 것
 */

/** NONE 없음 → MAKING 만드는 중 → DONE 다 됨 / FAILED 실패 */
enum class VoiceCloneStatus { NONE, MAKING, DONE, FAILED }

/** 내 목소리. voiceUrl은 DONE일 때 들어 보기 주소, failReason은 FAILED일 때 까닭(있으면) */
data class VoiceClone(val status: VoiceCloneStatus, val voiceUrl: String? = null, val failReason: String? = null)

/** 목소리 고르기에서 "내 목소리"를 고를 때 voice 값. 임시: 서버 값은 나중에 명세로 정해요 */
const val MY_VOICE_ID = "MY_CLONE"

/** 내 목소리를 만들 때 읽는 문장 5개(시안 6). 임시: 앱에 두었고 팀과 정하면 바꿔요 */
val VOICE_CLONE_SENTENCES = listOf(
    "오늘 저녁엔 친구들이랑 떡볶이 먹으러 가기로 했어",
    "어제 그 영화 진짜 웃기지 않았어?",
    "비 오는 날엔 창밖 보면서 노래 듣는 게 제일 좋아",
    "주말에 어디 갈지 아직 못 정했어",
    "고마워, 덕분에 오늘 정말 즐거웠어",
)
