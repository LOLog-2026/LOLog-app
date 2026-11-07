package com.lolog.app.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lolog.app.data.model.CardKind
import com.lolog.app.data.model.CardSummary
import com.lolog.app.data.model.displayNames
import com.lolog.app.ui.theme.LologColors
import com.lolog.app.ui.theme.LologShape
import com.lolog.app.util.dayLabel

/** 보관함·홈의 카드 한 장 (보관함·만화 시안 ①): 종류 칩 · 제목 · "11월 5일 · 홍대 카페 · 대사 4개" */
@Composable
fun CardItem(card: CardSummary, isNew: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth()
                .softCard()
                .then(if (isNew) Modifier.border(1.5.dp, LologColors.Accent, LologShape.md) else Modifier)
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            KindChip(card.kind)
            // 긴 제목은 한 줄 + "…"
            Text(card.summary, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Mic, null, tint = LologColors.Muted, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(3.dp))
                Text(cardMeta(card), fontSize = 12.sp, color = LologColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (isNew) PenMemo("새로!", Modifier.align(Alignment.TopEnd).offset(x = (-12).dp, y = (-10).dp), angle = 3f)
    }
}

/** "11월 5일 녹음 · 홍대 카페 · 대사 4개" (장소가 없으면 빼요). 날짜는 녹음한 날 */
fun cardMeta(card: CardSummary): String = listOfNotNull(
    dayLabel(card.recordedAt ?: card.createdAt).ifBlank { null }?.let { "$it 녹음" },
    card.place?.takeIf { it.isNotBlank() },
    card.lineCount?.let { "대사 ${it}개" },
).joinToString(" · ")

@Composable
private fun Meta(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = LologColors.Muted, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(3.dp))
        Text(text, fontSize = 12.sp, color = LologColors.Muted, maxLines = 1)
    }
}

/** "준호 · 민지", 3명 넘으면 "화자1 · 화자2 외 2명" */
fun speakerLine(names: List<String>): String =
    if (names.size <= 2) names.joinToString(" · ") else "${names.take(2).joinToString(" · ")} 외 ${names.size - 2}명"
