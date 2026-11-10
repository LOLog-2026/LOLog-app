package com.lolog.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lolog.app.data.model.SortOrder
import com.lolog.app.ui.theme.LologColors
import com.lolog.app.ui.theme.LologShape

/** 카드 목록 탭·화면 이름(옛 "보관함"). 이름을 바꿀 때는 이 값 하나만 고쳐요 */
const val LIBRARY_NAME = "에피소드"

/** 정렬 이름 (화면에 보이는 글자) */
val SortOrder.label: String
    get() = when (this) {
        SortOrder.NEWEST -> "최신순"
        SortOrder.OLDEST -> "오래된 순"
    }

/** 목록 화면 위 칸: 흰 바탕(제목 줄 + 칩 줄) + 아래 1dp 선. 아래 목록 칸은 화면이 회색(Canvas)으로 깔아요 */
@Composable
fun ListHeader(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(LologColors.Paper)) {
        content()
        HorizontalDivider(thickness = 1.dp, color = LologColors.Line)
    }
}

/** 제목 줄 오른쪽 "최신순 ⌄". 누르면 최신순 / 오래된 순 메뉴, 고른 쪽은 코랄 글자 + ✓ */
@Composable
fun SortMenu(sort: SortOrder, onSort: (SortOrder) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .clip(LologShape.pill)
                .clickable { open = true }
                .padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(sort.label, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = LologColors.Ink)
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "정렬 바꾸기", tint = LologColors.Muted, modifier = Modifier.size(20.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = LologColors.Paper) {
            SortOrder.entries.forEach { s ->
                val selected = s == sort
                DropdownMenuItem(
                    text = {
                        Text(
                            s.label, fontSize = 14.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) LologColors.Accent else LologColors.Ink,
                        )
                    },
                    trailingIcon = {
                        if (selected) Icon(Icons.Filled.Check, contentDescription = "선택됨", tint = LologColors.Accent, modifier = Modifier.size(18.dp))
                    },
                    onClick = { open = false; onSort(s) },
                )
            }
        }
    }
}
