package com.lolog.app.ui.screens.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Feedback
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lolog.app.ui.components.LologTopBar
import com.lolog.app.ui.theme.LologColors

// ── 관리자 홈 (시안 2, docs PR #59 admin.md) ──────────────
// 설정에서 관리자 계정에만 보이는 "관리자 화면" 줄로 들어와요.
// 공지사항은 누르면 공지 목록으로 가요. 나머지 두 기능은 아직 화면이 없어서 "준비 중"으로 두고 눌리지 않아요.
// 안 본 수 배지는 명세에 없어서 넣지 않아요

@Composable
fun AdminHomeScreen(onBack: () -> Unit, onNotices: () -> Unit) {
    Scaffold(
        containerColor = LologColors.Paper,
        topBar = { LologTopBar("관리자", onBack = onBack) },
    ) { pad ->
        Column(
            Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AdminRow(Icons.Outlined.Campaign, "공지사항", "쓰기 · 고치기 · 지우기", onClick = onNotices)
            AdminRow(Icons.Outlined.PersonOutline, "계정 관리", "찾기 · 정지 · 탈퇴시키기")
            AdminRow(Icons.Outlined.Feedback, "의견·오류 신고", "사용자가 보낸 글 · 처리 표시")
            Spacer(Modifier.height(4.dp))
            Text("관리자 계정에만 보여요.", fontSize = 12.sp, color = LologColors.Muted, modifier = Modifier.padding(horizontal = 4.dp))
        }
    }
}

/** 아이콘 + 굵은 제목 + 작은 설명. [onClick]이 없으면(화면이 생기기 전) 오른쪽에 회색 "준비 중"이고 눌리지 않아요 */
@Composable
private fun AdminRow(icon: ImageVector, title: String, sub: String, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(LologColors.Canvas)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(LologColors.Paper), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = LologColors.Ink, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(2.dp))
            Text(sub, fontSize = 12.sp, color = LologColors.Muted)
        }
        if (onClick != null) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = LologColors.Muted, modifier = Modifier.size(20.dp))
        } else {
            Text("준비 중", fontSize = 13.sp, color = LologColors.Muted)
        }
    }
}
