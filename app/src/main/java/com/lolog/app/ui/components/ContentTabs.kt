package com.lolog.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SmartDisplay
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lolog.app.ui.theme.LologColors
import com.lolog.app.ui.theme.LologShape

/**
 * 위 탭(컨텐츠 시안 v3): 왼쪽 정렬, 고른 탭은 굵은 글자 + 아래 28dp 코랄 밑줄, 맨 아래 1dp 선.
 * 컨텐츠 "4컷 / 미디어"와 친구 방 "4컷 / 미디어 / 대화"가 같이 써요.
 * [dotAt] 탭에 새 글이 있으면(그 탭을 보고 있지 않을 때) 글자 옆에 코랄 점
 */
@Composable
fun ContentTabs(tabs: List<String>, selected: Int, onSelect: (Int) -> Unit, dotAt: Int? = null) {
    Column {
        Row(Modifier.padding(horizontal = 12.dp)) {
            tabs.forEachIndexed { i, label ->
                val on = selected == i
                Column(
                    Modifier.clip(LologShape.sm).clickable { onSelect(i) }.padding(horizontal = 10.dp).padding(top = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(label, fontSize = 14.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, color = if (on) LologColors.Ink else LologColors.Muted)
                        if (dotAt == i && !on) {
                            Spacer(Modifier.width(4.dp))
                            Box(Modifier.size(5.dp).clip(CircleShape).background(LologColors.Accent))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.width(28.dp).height(2.5.dp).background(if (on) LologColors.Accent else Color.Transparent))
                }
            }
        }
        HorizontalDivider(thickness = 1.dp, color = LologColors.Line)
    }
}

/** 미디어 탭 임시 안내: 가운데 동그라미 재생 아이콘 + "쇼츠는 준비 중이에요" + 작은 회색 글. 쇼츠 API가 생기면 목록으로 바꿔요 */
@Composable
fun ShortsComingSoon(sub: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(56.dp).clip(CircleShape).background(LologColors.Paper).border(1.dp, LologColors.Line, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Outlined.SmartDisplay, null, tint = LologColors.Muted, modifier = Modifier.size(24.dp)) }
        Spacer(Modifier.height(14.dp))
        Text("쇼츠는 준비 중이에요", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = LologColors.Ink)
        Spacer(Modifier.height(6.dp))
        Text(sub, fontSize = 12.sp, color = LologColors.Muted, textAlign = TextAlign.Center, lineHeight = 18.sp)
    }
}
