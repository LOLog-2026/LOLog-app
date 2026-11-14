package com.lolog.app.data.model

/*
 * 내 목소리(FR-CHAR-05). 녹음 하나를 올리면 우리 서버(Jetson)가 내 목소리를 만들어 쇼츠에서 내 캐릭터가 내 목소리로 말해요.
 * 기준: LOLog-docs spec/characters.md "내 목소리", 서버는 LOLog-api #86.
 * 푸시 VOICE_CLONE_READY·VOICE_CLONE_FAILED는 알림·알림 목록·목소리 고르기 새로고침으로 받아요.
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

/**
 * 내 목소리를 만들 때 읽는 문장(characters.md "내 목소리" 녹음, 정함 11-14). 40글자라 보통 빠르기로 6~8초예요.
 * 문장이 하나라 앱은 보내지 않고, 서버가 같은 글자를 저장해 워커의 ref_text로 줘요.
 * 서버(LOLog-api VoiceCloneService.RECORD_SENTENCE)·ai(voice_clone.py RECORD_SENTENCE)와 글자가 같아야 해요. 바꾸면 셋 다 바꿔요
 */
const val VOICE_CLONE_SENTENCE = "오늘 친구랑 카페에 갔는데, 주문을 잘못해서 둘이 한참 웃었어요. 그래도 덕분에 즐거운 하루였어요."
