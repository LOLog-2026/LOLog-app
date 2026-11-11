package com.lolog.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

object LologShape {
    val sm = RoundedCornerShape(10.dp)
    val md = RoundedCornerShape(16.dp)
    val lg = RoundedCornerShape(24.dp)
    val pill = RoundedCornerShape(999.dp)
    /** 아래 시트 위쪽 모서리(11/10 확정 디자인) */
    val sheet = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
    /** 확인 창 모서리 */
    val dialog = RoundedCornerShape(20.dp)
}

private val colors = lightColorScheme(
    primary = LologColors.Accent,
    onPrimary = LologColors.Paper,
    primaryContainer = LologColors.AccentSoft,
    onPrimaryContainer = LologColors.Accent,
    secondary = LologColors.Ink,
    onSecondary = LologColors.Paper,
    background = LologColors.Paper,
    onBackground = LologColors.Ink,
    surface = LologColors.Paper,
    onSurface = LologColors.Ink,
    surfaceVariant = LologColors.Canvas,
    onSurfaceVariant = LologColors.Muted,
    surfaceContainer = LologColors.Paper,
    surfaceContainerHigh = LologColors.Paper,
    surfaceContainerLow = LologColors.Paper,
    outline = LologColors.Line,
    outlineVariant = LologColors.Line,
    error = LologColors.Rec,
    onError = LologColors.Paper,
    errorContainer = LologColors.RecSoft,
)

/** 앱은 라이트 테마만 써요(시안 기준) */
@Composable
fun LologTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = colors,
        typography = LologTypography,
        shapes = Shapes(small = LologShape.sm, medium = LologShape.md, large = LologShape.lg),
        content = content,
    )
}
