package com.lolog.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lolog.app.ui.theme.LologColors

/** 숫자 칸은 이 수를 넘지 않아요(‹ › 빼고). 쪽이 이보다 많으면 "…"로 줄여요 */
const val PAGE_NUMBER_SLOTS = 7

/**
 * 쪽 줄에 보여 줄 칸들. 숫자는 쪽 번호(1부터), null은 "…".
 * 7쪽 이하면 다 보여 주고, 8쪽 이상이면 지금 쪽 [current] 자리에 따라 7칸으로 줄여요.
 * - 앞쪽(1~4): 1 2 3 4 5 … N
 * - 가운데: 1 … p-1 p p+1 … N
 * - 뒤쪽(N-3~N): 1 … N-4 N-3 N-2 N-1 N
 */
fun pageNumbers(total: Int, current: Int): List<Int?> {
    if (total <= 0) return emptyList()
    if (total <= PAGE_NUMBER_SLOTS) return (1..total).toList()
    val p = current.coerceIn(1, total)
    return when {
        p <= 4 -> (1..5).toList() + listOf(null, total)
        p >= total - 3 -> listOf(1, null) + (total - 4..total).toList()
        else -> listOf(1, null, p - 1, p, p + 1, null, total)
    }
}

/**
 * 목록 아래 숫자 쪽 넘기기 줄: ‹ 1 2 3 4 5 … 12 ›.
 * 흰 바탕, 위 1dp 선. 지금 쪽은 코랄 둥근 네모에 흰 글자. 쪽이 1개 이하면 그리지 않아요
 */
@Composable
fun PageNumbers(total: Int, current: Int, onPage: (Int) -> Unit, modifier: Modifier = Modifier) {
    if (total <= 1) return
    Column(modifier.fillMaxWidth().background(LologColors.Paper)) {
        HorizontalDivider(thickness = 1.dp, color = LologColors.Line)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PageArrow(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "이전 쪽", enabled = current > 1) { onPage(current - 1) }
            pageNumbers(total, current).forEach { n ->
                if (n == null) {
                    // "…"은 눌리지 않고 읽어 주지도 않아요
                    Box(Modifier.widthIn(min = 24.dp).height(30.dp).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
                        Text("…", fontSize = 13.sp, color = LologColors.Muted)
                    }
                } else {
                    PageNumber(n, on = n == current) { onPage(n) }
                }
            }
            PageArrow(Icons.AutoMirrored.Filled.KeyboardArrowRight, "다음 쪽", enabled = current < total) { onPage(current + 1) }
        }
    }
}

/** 숫자 칸 하나(30dp 이상). 지금 쪽이면 코랄 바탕 */
@Composable
private fun PageNumber(n: Int, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.padding(horizontal = 2.dp).widthIn(min = 30.dp).height(30.dp).clip(RoundedCornerShape(8.dp))
            .background(if (on) LologColors.Accent else LologColors.Paper)
            .clickable(role = Role.Button, onClick = onClick)
            .clearAndSetSemantics { contentDescription = if (on) "${n}쪽, 지금 보는 쪽" else "${n}쪽" }
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "$n", fontSize = 13.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold,
            color = if (on) LologColors.Paper else LologColors.Ink,
        )
    }
}

/** ‹ › 화살표. 끝 쪽이면 회색으로 꺼져요 */
@Composable
private fun PageArrow(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(36.dp).clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = if (enabled) LologColors.Ink else LologColors.Line, modifier = Modifier.size(22.dp))
    }
}
