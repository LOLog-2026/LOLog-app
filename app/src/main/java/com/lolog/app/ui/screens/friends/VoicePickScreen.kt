package com.lolog.app.ui.screens.friends

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.lolog.app.data.model.AgeGroup
import com.lolog.app.data.model.DefaultCharacter
import com.lolog.app.data.model.ErrorCodes
import com.lolog.app.data.model.Gender
import com.lolog.app.data.model.defaultCharacterCacheKey
import com.lolog.app.data.repo.LologException
import com.lolog.app.data.repo.LologRepository
import com.lolog.app.ui.components.*
import com.lolog.app.ui.navigation.LocalContainer
import com.lolog.app.ui.theme.LologColors
import com.lolog.app.ui.theme.LologShape
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 내 캐릭터 화면의 "내 목소리" 줄을 보여 줄지. 서버(PATCH /api/me/profile/character/voice, voiceUrl·voice)가
 * 아직 없어서 꺼 둬요. 서버를 배포한 뒤 true로 바꿔요
 */
const val VOICE_PICK_ENABLED = false

/** 저장하고 돌아갈 때 내 캐릭터 화면 스낵바 문구 */
const val VOICE_PICKED = "목소리를 골랐어요"

/*
 * 내 목소리 고르기 (characters.md FR-CHAR-04, docs PR #59 초안, 시안 6~8)
 *  - 사진으로 새 목소리를 만들지 않아요. 기본 화자 목소리 중에서 골라요
 *  - 나이대·성별 거르기(화자 팝업과 같은 모양)
 *  - 맨 위 "고르지 않기"(voice = null, 쇼츠에서 알아서 골라요), 그 아래 voiceUrl이 있는 기본 화자만 한 줄씩
 *  - 처음엔 지금 저장된 값이 골라져 있고, 바뀌면 "이 목소리로 할게요"가 켜져요
 *  - 저장 PATCH → 성공하면 돌아가서 "목소리를 골랐어요"
 */

class VoicePickViewModel(private val repo: LologRepository) : ViewModel() {
    data class State(
        val loading: Boolean = true,
        val loadFailed: Boolean = false,
        val defaults: List<DefaultCharacter> = emptyList(),
        /** 지금 저장된 목소리(기본 화자 id, null = 안 고름) */
        val saved: String? = null,
        /** 화면에서 고른 목소리 */
        val selected: String? = null,
        val saving: Boolean = false,
        val toast: String? = null,
        val done: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    fun load() = viewModelScope.launch {
        _state.update { it.copy(loading = true, loadFailed = false) }
        coroutineScope {
            val profile = async { runCatching { repo.profile() } }
            val defaults = async { runCatching { repo.defaultCharacters() } }
            val p = profile.await()
            val d = defaults.await()
            if (p.isFailure || d.isFailure) {
                _state.update { it.copy(loading = false, loadFailed = true) }
                return@coroutineScope
            }
            val voice = p.getOrNull()?.character?.voice
            _state.update {
                it.copy(loading = false, defaults = d.getOrThrow(), saved = voice, selected = voice)
            }
        }
    }

    fun select(id: String?) = _state.update { it.copy(selected = id) }

    /** 재생 실패 때: 목록을 다시 받아(서명 주소 10분) 그 화자의 새 voiceUrl */
    suspend fun freshVoiceUrl(id: String): String? {
        val list = runCatching { repo.defaultCharacters() }.getOrNull() ?: return null
        _state.update { it.copy(defaults = list) }
        return list.firstOrNull { it.id == id }?.voiceUrl
    }

    fun save() = viewModelScope.launch {
        val s = _state.value
        if (s.saving || s.selected == s.saved) return@launch
        _state.update { it.copy(saving = true) }
        try {
            repo.setProfileCharacterVoice(s.selected)
            _state.update { it.copy(saving = false, saved = s.selected, done = true) }
        } catch (e: LologException) {
            _state.update { it.copy(saving = false, toast = voiceSaveError(e)) }
        }
    }

    fun toastShown() = _state.update { it.copy(toast = null) }
}

/** 400 UNKNOWN_CHARACTER(없거나 목소리 없는 id), 404 CHARACTER_NOT_FOUND(캐릭터 없음) → "목소리를 저장하지 못했어요" */
internal fun voiceSaveError(e: LologException): String = when (e.code) {
    ErrorCodes.UNKNOWN_CHARACTER, ErrorCodes.CHARACTER_NOT_FOUND -> "목소리를 저장하지 못했어요"
    else -> errorText(e)
}

@Composable
fun VoicePickScreen(onBack: () -> Unit, onDone: () -> Unit) {
    val repo = LocalContainer.current.repository
    val vm = viewModel { VoicePickViewModel(repo) }
    val state by vm.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val voice = rememberVoicePlayer(onError = { scope.launch { snackbar.showSnackbar(VOICE_LOAD_FAILED) } })
    var age by remember { mutableStateOf<AgeGroup?>(null) }
    var gender by remember { mutableStateOf<Gender?>(null) }

    LaunchedEffect(Unit) { vm.load() }
    LaunchedEffect(state.done) { if (state.done) onDone() }
    LaunchedEffect(state.toast) {
        val msg = state.toast ?: return@LaunchedEffect
        snackbar.showSnackbar(msg)
        vm.toastShown()
    }

    Scaffold(
        containerColor = LologColors.Paper,
        snackbarHost = { LologSnackbarHost(snackbar) },
        topBar = { LologTopBar("내 목소리 고르기", onBack = onBack) },
        bottomBar = {
            if (!state.loading && !state.loadFailed) {
                Box(Modifier.fillMaxWidth().background(LologColors.Paper).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
                    PrimaryButton(
                        "이 목소리로 할게요", onClick = { voice.stop(); vm.save() },
                        enabled = state.selected != state.saved, loading = state.saving,
                    )
                }
            }
        },
    ) { pad ->
        when {
            state.loading -> SkeletonList(Modifier.padding(pad), count = 4)
            state.loadFailed -> ListErrorState(onRetry = { vm.load() }, modifier = Modifier.padding(pad), title = "불러오지 못했어요")
            else -> {
                val shown = state.defaults.filter {
                    it.voiceUrl != null && (age == null || it.ageGroup == age) && (gender == null || it.gender == gender)
                }
                LazyColumn(
                    Modifier.padding(pad).fillMaxSize(),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        Text(
                            "기본 화자 24명의 목소리 중에서 골라요. 사진으로 새 목소리를 만들지는 않아요.",
                            fontSize = 13.sp, color = LologColors.Muted, lineHeight = 19.sp,
                        )
                        Spacer(Modifier.height(12.dp))
                        // 거르기: 전체 · 어린이 · 청년 · 중장년 / 여 · 남 (화자 팝업과 같은 모양)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterPill("전체", age == null && gender == null) { age = null; gender = null }
                            AgeGroup.entries.forEach { a -> FilterPill(a.label, age == a) { age = if (age == a) null else a } }
                            Gender.entries.forEach { g -> FilterPill(g.label, gender == g) { gender = if (gender == g) null else g } }
                        }
                        VoiceRecordingNote(voice, Modifier.padding(top = 12.dp))
                        Spacer(Modifier.height(4.dp))
                    }
                    item(key = "none") {
                        VoiceRow(
                            selected = state.selected == null, onClick = { vm.select(null) },
                            image = {
                                Box(Modifier.fillMaxSize().background(LologColors.Canvas), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Outlined.Person, null, tint = LologColors.Muted, modifier = Modifier.size(24.dp))
                                }
                            },
                            title = "고르지 않기", subtitle = "쇼츠에서 알아서 골라요",
                        )
                    }
                    items(shown, key = { it.id }) { d ->
                        VoiceRow(
                            selected = state.selected == d.id, onClick = { vm.select(d.id) },
                            image = {
                                d.thumbnailUrl?.let { url ->
                                    AsyncImage(
                                        model = characterImage(url, defaultCharacterCacheKey(d.id)), contentDescription = null,
                                        contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            },
                            title = d.label,
                            subtitle = if (voice.playingId == d.id) "$VOICE_GREETING 듣는 중" else VOICE_GREETING,
                        ) {
                            VoicePreviewButton(d.voiceUrl, d.id, voice, freshUrl = { vm.freshVoiceUrl(d.id) }, compact = true)
                            Spacer(Modifier.width(10.dp))
                        }
                    }
                }
            }
        }
    }
}

/** 한 줄: 그림 + 이름·작은 글 + (재생 버튼) + 라디오. 고르면 코랄 테두리·연한 코랄 바탕 */
@Composable
private fun VoiceRow(
    selected: Boolean,
    onClick: () -> Unit,
    image: @Composable () -> Unit,
    title: String,
    subtitle: String,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().clip(LologShape.md)
            .background(if (selected) LologColors.Accent.copy(alpha = 0.06f) else LologColors.Paper)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) LologColors.Accent else LologColors.Line, LologShape.md)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(LologColors.Canvas)) { image() }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = LologColors.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, fontSize = 12.sp, color = LologColors.Muted, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
        }
        Spacer(Modifier.width(8.dp))
        trailing()
        Radio(selected)
    }
}

/** 라디오: 고르면 코랄 고리 + 가운데 점 */
@Composable
private fun Radio(selected: Boolean) {
    Box(
        Modifier.size(22.dp).clip(CircleShape)
            .border(if (selected) 2.dp else 1.5.dp, if (selected) LologColors.Accent else LologColors.Line, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Box(Modifier.size(10.dp).clip(CircleShape).background(LologColors.Accent))
    }
}
