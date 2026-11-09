package com.lolog.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lolog.app.ui.theme.LologColors

enum class StepState { DONE, NOW, WAIT, FAILED }

data class StepItem(val title: String, val desc: String, val state: StepState)

/** 세로 단계 표시 (시안 06·11·21번) */
@Composable
fun StepList(steps: List<StepItem>, modifier: Modifier = Modifier) {
    Column(modifier) {
        steps.forEachIndexed { i, step ->
            Row(Modifier.height(IntrinsicSize.Min)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(28.dp)) {
                    StepDot(i + 1, step.state)
                    if (i < steps.lastIndex) {
                        Box(
                            Modifier.width(2.dp).weight(1f)
                                .background(if (step.state == StepState.DONE) LologColors.Accent else LologColors.Line),
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.padding(bottom = 18.dp)) {
                    Text(
                        step.title, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        color = when (step.state) {
                            StepState.WAIT -> LologColors.Muted
                            StepState.FAILED -> LologColors.Rec
                            else -> LologColors.Ink
                        },
                    )
                    if (step.desc.isNotBlank()) Text(step.desc, fontSize = 12.sp, color = LologColors.Muted, lineHeight = 17.sp)
                }
            }
        }
    }
}

@Composable
private fun StepDot(n: Int, state: StepState) {
    val base = Modifier.size(28.dp).clip(CircleShape)
    when (state) {
        StepState.DONE -> Box(base.background(LologColors.Accent), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Check, null, tint = LologColors.Paper, modifier = Modifier.size(16.dp))
        }
        StepState.NOW -> Box(base.background(LologColors.AccentSoft).border(2.dp, LologColors.Accent, CircleShape), contentAlignment = Alignment.Center) {
            Text("$n", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = LologColors.Accent)
        }
        StepState.WAIT -> Box(base.background(LologColors.Paper).border(2.dp, LologColors.Line, CircleShape), contentAlignment = Alignment.Center) {
            Text("$n", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = LologColors.Muted)
        }
        StepState.FAILED -> Box(base.background(LologColors.Rec), contentAlignment = Alignment.Center) {
            Text("!", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = LologColors.Paper)
        }
    }
}
