package com.lolog.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.lolog.app.R

val Pretendard = FontFamily(
    Font(R.font.pretendard_regular, FontWeight.Normal),
    Font(R.font.pretendard_medium, FontWeight.Medium),
    Font(R.font.pretendard_semibold, FontWeight.SemiBold),
    Font(R.font.pretendard_bold, FontWeight.Bold),
    Font(R.font.pretendard_bold, FontWeight.ExtraBold),
    Font(R.font.pretendard_bold, FontWeight.Black),
)

/** 손글씨는 메모에만 */
val PenScript = FontFamily(Font(R.font.nanum_pen_script, FontWeight.Normal))

private fun s(size: Int, weight: FontWeight, line: Double = 1.5) = TextStyle(
    fontFamily = Pretendard, fontWeight = weight, fontSize = size.sp, lineHeight = (size * line).sp,
)

val LologTypography = Typography(
    headlineLarge = s(26, FontWeight.ExtraBold, 1.3),   // 상세 제목
    headlineMedium = s(22, FontWeight.Bold, 1.3),
    titleLarge = s(19, FontWeight.Bold, 1.35),          // 앱바
    titleMedium = s(16, FontWeight.Bold, 1.35),         // 카드 제목
    titleSmall = s(15, FontWeight.SemiBold, 1.4),
    bodyLarge = s(15, FontWeight.Normal),
    bodyMedium = s(14, FontWeight.Normal),
    bodySmall = s(13, FontWeight.Normal, 1.45),
    labelLarge = s(16, FontWeight.Bold, 1.2),           // 버튼
    labelMedium = s(12, FontWeight.SemiBold, 1.3),      // 칩·캡션
    labelSmall = s(11, FontWeight.SemiBold, 1.3),       // 탭
)

object LologText {
    val memo = TextStyle(fontFamily = PenScript, fontSize = 20.sp, lineHeight = 20.sp, color = LologColors.Accent)
    val timer = TextStyle(fontFamily = Pretendard, fontWeight = FontWeight.SemiBold, fontSize = 40.sp, letterSpacing = (-1).sp)
    val stat = TextStyle(fontFamily = Pretendard, fontWeight = FontWeight.ExtraBold, fontSize = 26.sp, lineHeight = 29.sp, color = LologColors.Accent)
    val brand = TextStyle(fontFamily = Pretendard, fontWeight = FontWeight.Black, fontSize = 24.sp, letterSpacing = (-1).sp, color = LologColors.Accent)
}
