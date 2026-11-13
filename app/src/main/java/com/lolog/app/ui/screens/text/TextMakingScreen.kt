package com.lolog.app.ui.screens.text

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lolog.app.data.model.TextDetail
import com.lolog.app.data.model.TextStatus
import com.lolog.app.data.repo.LologException
import com.lolog.app.data.repo.LologRepository
import com.lolog.app.ui.components.GhostButton
import com.lolog.app.ui.components.ListErrorState
import com.lolog.app.ui.components.LologTopBar
import com.lolog.app.ui.components.PrimaryButton
import com.lolog.app.ui.components.TypingDots
import com.lolog.app.ui.navigation.LocalContainer
import com.lolog.app.ui.screens.comic.StepPills
import com.lolog.app.ui.theme.LologColors
import com.lolog.app.ui.theme.LologShape
import com.lolog.app.ui.theme.LologText
import com.lolog.app.util.TextDraft
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/*
 * 카드 만드는 중(글로 카드 만들기 시안 4번). 직접 쓰기에서 보내면 들어와요.
 *  - 쇼츠 만드는 중(VideoScreen Making)과 같은 판: 점 3개 · 문구 · 단계 알약 받음 › 만드는 중 › 완성
 *  - 아래에 보낸 글(제목 · 본문 앞부분)
 *  - 5초마다 GET /api/texts/{id}. DONE이면 그 카드로 바꿔 열어요
 *  - FAILED면 "만들지 못했어요" + [다시 쓰기](쓴 글을 임시 저장에 다시 넣고 쓰기 화면으로)
 */

class TextMakingViewModel(private val repo: LologRepository, private val textId: Long) : ViewModel() {
    data class State(val text: TextDetail? = null, val error: Boolean = false)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    /** QUEUED인 동안 5초마다 다시 받아요. 못 받으면 멈추고 다시 시도 버튼 */
    fun watch() = viewModelScope.launch {
        _state.update { it.copy(error = false) }
        while (true) {
            try {
                val t = repo.text(textId)
                _state.update { it.copy(text = t, error = false) }
                if (t.status != TextStatus.QUEUED) return@launch
            } catch (e: LologException) {
                _state.update { it.copy(error = true) }
                return@launch
            }
            delay(5_000)
        }
    }
}

/** 단계 알약 3개 */
private val TEXT_STEPS = listOf("받음", "만드는 중", "완성")

@Composable
fun TextMakingScreen(textId: Long, onBack: () -> Unit, onDone: (cardId: Long) -> Unit, onRewrite: () -> Unit, onHome: () -> Unit) {
    val repo = LocalContainer.current.repository
    val vm = viewModel(key = "text-$textId") { TextMakingViewModel(repo, textId) }
    val state by vm.state.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) { vm.watch() }
    val text = state.text
    LaunchedEffect(text?.status, text?.cardId) {
        val cardId = text?.cardId
        if (text?.status == TextStatus.DONE && cardId != null) onDone(cardId)
    }

    Scaffold(
        containerColor = LologColors.Paper,
        topBar = { LologTopBar("에피소드 만들기", onBack) },
        bottomBar = {
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (text?.status == TextStatus.FAILED) {
                    PrimaryButton("다시 쓰기", {
                        // 쓴 글을 임시 저장에 다시 넣어 쓰기 화면이 불러오게 해요
                        TextDraft.save(context, TextDraft.Draft(text.title, text.body.orEmpty(), text.place.orEmpty(), text.situation.orEmpty()))
                        onRewrite()
                    })
                }
                GhostButton("홈으로", onHome)
            }
        },
    ) { pad ->
        when {
            text == null && state.error -> ListErrorState(onRetry = { vm.watch() }, modifier = Modifier.padding(pad), title = "글을 불러오지 못했어요")
            text == null -> Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { TypingDots() }
            else -> Making(text, Modifier.padding(pad))
        }
    }
}

@Composable
private fun Making(text: TextDetail, modifier: Modifier) {
    val failed = text.status == TextStatus.FAILED
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        // Canvas 판: 점 3개 + 문구 + 단계 알약 (실패면 점과 알약 없이 안내만)
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(LologColors.Canvas).padding(vertical = 20.dp, horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!failed) {
                TypingDots(dot = 6.dp)
                Spacer(Modifier.height(12.dp))
            }
            Text(
                if (failed) "만들지 못했어요" else "글로 에피소드를 만들고 있어요",
                fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = if (failed) LologColors.Rec else LologColors.Ink,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (failed) "글을 조금 고쳐서 다시 보내 주세요." else "앱을 닫아도 돼요. 다 되면 알려 드려요.",
                fontSize = 12.5.sp, color = LologColors.Muted, textAlign = TextAlign.Center,
            )
            if (!failed) {
                Spacer(Modifier.height(14.dp))
                // QUEUED = 만드는 중 진행, DONE = 완성
                StepPills(if (text.status == TextStatus.DONE) 2 else 1, TEXT_STEPS)
            }
        }
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("보낸 글", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = LologColors.Muted)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            // 제목은 손글씨 파랑. 길면 한 줄 + "…"
            Text(text.title, style = LologText.memo, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        }
        Spacer(Modifier.height(8.dp))
        text.body?.takeIf { it.isNotBlank() }?.let { body ->
            Text(
                body, fontSize = 13.sp, lineHeight = 20.sp, color = LologColors.Muted, maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().clip(LologShape.sm).background(LologColors.Canvas).padding(horizontal = 14.dp, vertical = 12.dp),
            )
        }
    }
}
