package com.lolog.app.ui.theme

import androidx.compose.ui.graphics.Color

/** 디자인 토큰. 11/8 B안(흰 바탕 + 코랄 메인 + 노랑 포인트, 파랑은 메모에만) */
object LologColors {
    val Accent = Color(0xFFE8503F)      // 코랄(로고 본색). 11/8 B안: 파랑 → 코랄
    val AccentPress = Color(0xFFC93E2F)
    val AccentSoft = Color(0xFFFFF1D6)  // 연한 노랑
    val Ink = Color(0xFF191F28)
    val Muted = Color(0xFF6B7684)
    val Line = Color(0xFFE5E8EB)
    val Rule = Color(0xFFF4F5F7)        // 노트 줄(B안: 아주 옅게)
    val Canvas = Color(0xFFF2F4F6)      // 작은 칸 회색(바탕은 이제 흰색 Paper)
    val Pen = Color(0xFF2B4FD8)         // 볼펜 파랑: 손글씨 메모와 로고 선에만
    val Paper = Color(0xFFFFFFFF)
    val Highlight = Color(0xFFFFD95A)   // 형광펜(B안: 첫 화면 얼굴과 같은 노랑)

    val Laugh = Color(0xFFFFB020)
    val LaughSoft = Color(0xFFFFF4DB)
    val Absurd = Color(0xFFC94FD8)      // 황당: 코랄과 안 겹치게 자주색(B안)
    val AbsurdSoft = Color(0xFFF7E6FA)
    val Memory = Color(0xFF3DB39E)
    val MemorySoft = Color(0xFFE1F5F1)
    val MemoryText = Color(0xFF1F7A69)

    val Rec = Color(0xFFE8503F)
    val RecSoft = Color(0xFFFDE3DD)
    val Ok = Color(0xFF2FA36B)

    val Naver = Color(0xFF03C75A)
}
