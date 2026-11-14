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
import com.lolog.app.data.model.MY_VOICE_ID
import com.lolog.app.data.model.PushType
import com.lolog.app.data.model.VoiceClone
import com.lolog.app.data.model.VoiceCloneStatus
import com.lolog.app.data.model.defaultCharacterCacheKey
import com.lolog.app.data.repo.LologException
import com.lolog.app.data.repo.LologRepository
import com.lolog.app.ui.badges.OnPush
import com.lolog.app.ui.components.*
import com.lolog.app.ui.navigation.LocalContainer
import com.lolog.app.ui.theme.LologColors
import com.lolog.app.ui.theme.LologShape
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 저장하고 돌아갈 때 내 캐릭터 화면 스낵바 문구 */
const val VOICE_PICKED = "목소리를 골랐어요"

/*
 * 내 목소리 고르기 (characters.md FR-CHAR-04, docs PR #59 초안, 시안 6~8)
 *  - 사진으로 새 목소리를 만들지 않아요. 기본 화자 목소리 중에서 골라요
 *  - 나이대·성별 거르기(화자 팝업과 같은 모양)
 *  - 맨 위 "고르지 않기"(voice = null, 쇼츠에서 알아서 골라요), 그 아래 voiceUrl이 있는 기본 화자만 한 줄씩
 *  - 처음엔 지금 저장된 값이 골라져 있고, 바뀌면 "이 목소리로 할게요"가 켜져요
 *  - 저장 PATCH → 성공하면 돌아가서 "목소리를 골랐어요"
 *  - 맨 위 "내 목소리" 칸(내 목소리 만들기 시안 1·8·9·10, MyVoiceSection.kt). 다 됐으면 voice = "self"로 같은 저장 흐름.
 *    만드는 중이면 화면에 있는 동안 5초마다 상태를 다시 받아요. 기준 docs PR #70(병합 전), 목업만(실제 서버는 NOT_READY)
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
        /** 내 목소리(만든 적 없거나 못 받았으면 null) */
        val clone: VoiceClone? = null,
        /** 내 목소리 상태를 한 번이라도 받아 봤는지(성공·실패 모두). 받기 전에는 칸을 그리지 않아요 */
        val cloneLoaded: Boolean = false,
        /** 서버가 아직 없음(NOT_READY). 칸은 보이되 누르면 스낵바 */
        val cloneNotReady: Boolean = false,
        /** 내 캐릭터 그림(다 된 내 목소리 줄에 써요) */
        val myThumbnail: String? = null,
        val deleting: Boolean = false,
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
            val character = p.getOrNull()?.character
            val voice = character?.voice
            _state.update {
                it.copy(loading = false, defaults = d.getOrThrow(), saved = voice, selected = voice, myThumbnail = character?.thumbnailUrl)
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

    /** 내 목소리 상태 다시 받기. 실패하면 지난 값 그대로(NOT_READY면 표시만) */
    suspend fun refreshClone() {
        try {
            val c = repo.getVoiceClone()
            // 만들지 못하면 서버가 골랐던 "self"를 null로 돌려요(명세 #70)
            val failed = c?.status == VoiceCloneStatus.FAILED
            _state.update {
                it.copy(
                    clone = c, cloneLoaded = true, cloneNotReady = false,
                    saved = if (failed) it.saved.takeUnless { v -> v == MY_VOICE_ID } else it.saved,
                    selected = if (failed) it.selected.takeUnless { v -> v == MY_VOICE_ID } else it.selected,
                )
            }
        } catch (e: LologException) {
            _state.update { it.copy(cloneLoaded = true, cloneNotReady = e.code == ErrorCodes.NOT_READY) }
        }
    }

    /** 내 목소리 재생 실패 때: 상태를 다시 받아 새 previewUrl */
    suspend fun freshMyVoiceUrl(): String? {
        refreshClone()
        return _state.value.clone?.previewUrl
    }

    /** ⋮ 시트의 친구 쇼츠 스위치. 먼저 바꿔 보여 주고, 실패하면 되돌려요 */
    fun setShare(share: Boolean) = viewModelScope.launch {
        val before = _state.value.clone ?: return@launch
        _state.update { it.copy(clone = before.copy(shareWithFriends = share)) }
        try {
            repo.setVoiceCloneShare(share)
        } catch (e: LologException) {
            _state.update { it.copy(clone = it.clone?.copy(shareWithFriends = before.shareWithFriends), toast = errorText(e)) }
        }
    }

    /** 내 목소리 지우기. 고른 목소리가 "self"였으면 서버(목업)가 안 고름으로 돌려요 */
    fun deleteClone() = viewModelScope.launch {
        if (_state.value.deleting) return@launch
        _state.update { it.copy(deleting = true) }
        try {
            repo.deleteVoiceClone()
            _state.update {
                it.copy(
                    deleting = false, clone = null,
                    saved = it.saved.takeUnless { v -> v == MY_VOICE_ID },
                    selected = it.selected.takeUnless { v -> v == MY_VOICE_ID },
                    toast = "내 목소리를 지웠어요",
                )
            }
        } catch (e: LologException) {
            _state.update { it.copy(deleting = false, toast = errorText(e)) }
        }
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
fun VoicePickScreen(onBack: () -> Unit, onDone: () -> Unit, onMakeVoice: () -> Unit = {}) {
    val repo = LocalContainer.current.repository
    val vm = viewModel { VoicePickViewModel(repo) }
    val state by vm.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val voice = rememberVoicePlayer(onError = { scope.launch { snackbar.showSnackbar(VOICE_LOAD_FAILED) } })
    var age by remember { mutableStateOf<AgeGroup?>(null) }
    var gender by remember { mutableStateOf<Gender?>(null) }

    var voiceSheet by remember { mutableStateOf(false) }
    var askDelete by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.load() }
    // 내 목소리: 화면에 돌아올 때마다 한 번, 만드는 중이면 화면에 있는 동안 5초마다 다시 받아요
    val making = state.clone?.status == VoiceCloneStatus.PROCESSING
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(making, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            vm.refreshClone()
            while (making) {
                delay(5_000)
                vm.refreshClone()
            }
        }
    }
    // 다 만듦·실패 푸시가 오면 5초를 기다리지 않고 바로 다시 받아요(푸시는 화면 밖을 위한 보조, characters.md 흐름 4)
    OnPush(PushType.VOICE_CLONE_READY, PushType.VOICE_CLONE_FAILED) { vm.refreshClone() }
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
                            "기본 목소리 24개 중에서 고르거나, 내 목소리를 만들어 쓸 수 있어요.",
                            fontSize = 13.sp, color = LologColors.Muted, lineHeight = 19.sp,
                        )
                        Spacer(Modifier.height(14.dp))
                        VoiceSectionLabel("내 목소리")
                    }
                    if (state.cloneLoaded) item(key = "mine") {
                        when (myVoiceRow(state.clone?.status, voice.recording)) {
                            MyVoiceRowKind.MAKE, MyVoiceRowKind.FAILED, MyVoiceRowKind.DISABLED -> Column {
                                MakeMyVoiceRow(
                                    failed = state.clone?.status == VoiceCloneStatus.FAILED,
                                    enabled = !voice.recording,
                                    onClick = {
                                        if (state.cloneNotReady) scope.launch { snackbar.showSnackbar(VOICE_CLONE_NOT_READY) }
                                        else { voice.stop(); onMakeVoice() }
                                    },
                                )
                                if (voice.recording) VoiceCloneRecordingNote(Modifier.padding(top = 8.dp))
                            }
                            MyVoiceRowKind.MAKING -> MakingMyVoiceRow()
                            MyVoiceRowKind.READY -> VoiceRow(
                                selected = state.selected == MY_VOICE_ID, onClick = { vm.select(MY_VOICE_ID) },
                                image = { MyVoiceImage(state.myThumbnail) },
                                title = "내 목소리", titleTag = { MyRecordingTag() },
                                subtitle = if (voice.playingId == MY_VOICE_ID) "듣는 중" else shareNote(state.clone?.shareWithFriends == true),
                            ) {
                                VoicePreviewButton(state.clone?.previewUrl, MY_VOICE_ID, voice, freshUrl = { vm.freshMyVoiceUrl() }, compact = true)
                                MyVoiceMoreButton { voiceSheet = true }
                                Spacer(Modifier.width(4.dp))
                            }
                        }
                    }
                    item {
                        VoiceSectionLabel("기본 목소리", Modifier.padding(top = 8.dp))
                        Spacer(Modifier.height(8.dp))
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

    if (voiceSheet) {
        MyVoiceSheet(
            shareWithFriends = state.clone?.shareWithFriends == true,
            onShareChange = vm::setShare,
            onDismiss = { voiceSheet = false },
            onRerecord = { voiceSheet = false; voice.stop(); onMakeVoice() },
            onDelete = { voiceSheet = false; askDelete = true },
        )
    }
    if (askDelete) {
        LologDialog(
            title = "내 목소리를 지울까요?",
            body = VOICE_CLONE_DELETE_NOTE,
            confirmText = "지우기", danger = true, loading = state.deleting,
            onConfirm = { voice.stop(); vm.deleteClone(); askDelete = false },
            onDismiss = { askDelete = false },
        )
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
    titleTag: (@Composable () -> Unit)? = null,
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = LologColors.Ink, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                if (titleTag != null) { Spacer(Modifier.width(6.dp)); titleTag() }
            }
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
