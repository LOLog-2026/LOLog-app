package com.lolog.app.ui.screens.friends

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.lolog.app.data.model.VoiceCloneStatus
import com.lolog.app.ui.components.LologSheet
import com.lolog.app.ui.theme.LologColors
import com.lolog.app.ui.theme.LologShape

/*
 * 목소리 고르기 맨 위 "내 목소리" 칸(내 목소리 만들기 시안 2판 1·8·9·10·11·12, 기준 LOLog-docs PR #70 병합 전)
 *  - 없음(null): 코랄 점선 "내 목소리 만들기 … 시작 ›"
 *  - 실패: 같은 모양에 이유를 나누지 않은 문구 하나 + "다시 ›"(failReason은 받아도 쓰지 않아요)
 *  - 만드는 중: 시계 줄, 고를 수 없음
 *  - 다 됨: 내 캐릭터 그림 + "내 목소리" + 꼬리표 "내 녹음" + 작은 글(친구 쇼츠 동의) + 들어 보기 + ⋮ + 라디오
 *  - 대화 녹음 중이면 만들기 칸을 끄고 회색 안내
 */

/** 내 목소리 칸 모양 */
enum class MyVoiceRowKind { MAKE, FAILED, MAKING, READY, DISABLED }

/** 상태(만든 적 없거나 못 받았으면 null)와 대화 녹음 중인지로 칸 모양을 정해요. 다 됐으면 녹음 중에도 고를 수 있어요 */
internal fun myVoiceRow(status: VoiceCloneStatus?, recording: Boolean): MyVoiceRowKind = when {
    status == VoiceCloneStatus.READY -> MyVoiceRowKind.READY
    status == VoiceCloneStatus.PROCESSING -> MyVoiceRowKind.MAKING
    recording -> MyVoiceRowKind.DISABLED
    status == VoiceCloneStatus.FAILED -> MyVoiceRowKind.FAILED
    else -> MyVoiceRowKind.MAKE
}

/** 다 된 줄의 작은 글: 친구 쇼츠 동의 */
internal fun shareNote(shareWithFriends: Boolean): String =
    if (shareWithFriends) "친구 쇼츠에도 나와요" else "친구 쇼츠엔 기본 목소리"

/** 대화 녹음 중 안내(VoiceRecordingNote와 같은 모양) */
const val VOICE_CLONE_RECORDING_NOTE = "대화를 녹음하는 중에는 내 목소리를 만들 수 없어요. 녹음을 마친 뒤에 해 주세요."

/** 서버가 아직 없을 때(Remote NOT_READY) 스낵바 */
const val VOICE_CLONE_NOT_READY = "아직 서버 준비 중이에요"

/** 실패 문구(이유를 나누지 않아요). 칸에서는 마침표에서 두 줄로 나눠 보여요 */
const val VOICE_CLONE_FAILED_TITLE = "내 목소리를 만들지 못했어요"
const val VOICE_CLONE_FAILED_BODY = "조용한 곳에서 다시 녹음해 주세요"

/** 지우기 안내(⋮ 시트·확인 창) */
const val VOICE_CLONE_DELETE_NOTE = "지우면 녹음 파일도 같이 지워져요. 내 목소리를 골라 뒀다면 \"고르지 않기\"로 바뀌어요."

/** 소제목("내 목소리"·"기본 목소리") */
@Composable
internal fun VoiceSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = LologColors.Muted, modifier = modifier)
}

/** 회색 안내 한 칸(VoiceRecordingNote와 같은 모양) */
@Composable
internal fun VoiceCloneRecordingNote(modifier: Modifier = Modifier) {
    Text(
        VOICE_CLONE_RECORDING_NOTE, fontSize = 12.sp, color = LologColors.Muted, lineHeight = 18.sp,
        modifier = modifier.fillMaxWidth().clip(LologShape.sm).background(LologColors.Canvas).padding(horizontal = 14.dp, vertical = 12.dp),
    )
}

/** 만들기 칸: 코랄 점선 + 연한 코랄 바탕. [failed]면 실패 문구와 "다시 ›", [enabled]가 false면 회색(대화 녹음 중) */
@Composable
internal fun MakeMyVoiceRow(failed: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val color = if (enabled) LologColors.Accent else LologColors.Line
    Row(
        Modifier.fillMaxWidth().clip(LologShape.md)
            .background(if (enabled) LologColors.Accent.copy(alpha = 0.06f) else LologColors.Canvas)
            .drawBehind {
                drawRoundRect(
                    color, cornerRadius = CornerRadius(16.dp.toPx()),
                    style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))),
                )
            }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(if (enabled) LologColors.Accent else LologColors.Line), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Mic, null, tint = LologColors.Paper, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (failed) VOICE_CLONE_FAILED_TITLE else "내 목소리 만들기",
                fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (enabled) LologColors.Ink else LologColors.Muted,
            )
            Text(
                if (failed) VOICE_CLONE_FAILED_BODY else "문장 하나를 읽으면 내 캐릭터가 내 목소리로 말해요",
                fontSize = 12.sp, color = LologColors.Muted, lineHeight = 17.sp, modifier = Modifier.padding(top = 2.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            if (failed) "다시 ›" else "시작 ›",
            fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (enabled) LologColors.Accent else LologColors.Muted,
        )
    }
}

/** 만드는 중 줄: 고를 수 없어요 */
@Composable
internal fun MakingMyVoiceRow() {
    Row(
        Modifier.fillMaxWidth().clip(LologShape.md).border(1.dp, LologColors.Line, LologShape.md)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(LologColors.RecSoft), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Schedule, null, tint = LologColors.Accent, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("내 목소리", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = LologColors.Ink)
            Text("만드는 중이에요 · 조금 시간이 걸려요", fontSize = 12.sp, color = LologColors.Muted, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** 다 된 내 목소리 줄의 그림: 내 캐릭터(없으면 마이크) */
@Composable
internal fun MyVoiceImage(thumbnailUrl: String?) {
    if (thumbnailUrl != null) {
        AsyncImage(thumbnailUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    } else {
        Box(Modifier.fillMaxSize().background(LologColors.RecSoft), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Mic, null, tint = LologColors.Accent, modifier = Modifier.size(22.dp))
        }
    }
}

/** 이름 옆 작은 꼬리표 "내 녹음" */
@Composable
internal fun MyRecordingTag() {
    Text(
        "내 녹음", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = LologColors.Accent,
        modifier = Modifier.clip(LologShape.pill).background(LologColors.RecSoft).padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/** ⋮ 버튼 */
@Composable
internal fun MyVoiceMoreButton(onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(32.dp)) {
        Icon(Icons.Filled.MoreVert, "내 목소리 더 보기", tint = LologColors.Muted, modifier = Modifier.size(20.dp))
    }
}

/** ⋮ → 아래 시트: 친구 쇼츠 스위치 / 다시 녹음하기 / 내 목소리 지우기(시안 10) */
@Composable
internal fun MyVoiceSheet(
    shareWithFriends: Boolean,
    onShareChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onRerecord: () -> Unit,
    onDelete: () -> Unit,
) {
    LologSheet(onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Row(
                Modifier.fillMaxWidth()
                    .toggleable(value = shareWithFriends, role = Role.Switch, onValueChange = onShareChange)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("친구 쇼츠에도 내 목소리", fontSize = 15.sp, color = LologColors.Ink, modifier = Modifier.weight(1f))
                Switch(
                    checked = shareWithFriends, onCheckedChange = null,
                    colors = SwitchDefaults.colors(checkedTrackColor = LologColors.Accent, checkedThumbColor = LologColors.Paper),
                )
            }
            HorizontalDivider(color = LologColors.Line)
            Text(
                "다시 녹음하기", fontSize = 15.sp, color = LologColors.Ink,
                modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onRerecord).padding(vertical = 14.dp),
            )
            HorizontalDivider(color = LologColors.Line)
            Text(
                "내 목소리 지우기", fontSize = 15.sp, color = LologColors.Accent,
                modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onDelete).padding(vertical = 14.dp),
            )
            Text(VOICE_CLONE_DELETE_NOTE, fontSize = 12.sp, color = LologColors.Muted, lineHeight = 18.sp)
        }
    }
}
