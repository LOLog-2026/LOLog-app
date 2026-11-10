package com.lolog.app.ui.screens.friends

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.lolog.app.data.model.ProfileCharacter
import com.lolog.app.data.model.ProfileCharacterStatus
import com.lolog.app.data.repo.LologException
import com.lolog.app.data.repo.LologRepository
import com.lolog.app.ui.components.*
import com.lolog.app.ui.navigation.LocalContainer
import com.lolog.app.ui.theme.LologColors
import com.lolog.app.util.ProfilePhoto
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/*
 * 내 캐릭터(프로필 캐릭터) 만들기 (characters.md 3장)
 *  - 사진 한 장을 고르면 폰 안에서 얼굴이 한 명인지 보고 PUT /api/me/profile/character. 202면 PROCESSING
 *  - PROCESSING: 그리는 중. 다시 만드는 중이면 예전 그림을 그대로 보여 줘요. 화면에 있는 동안 5초마다 상태를 다시 봐요
 *  - READY: 그림. 다른 사진으로 다시 만들기, 지우기
 *  - FAILED: 실패 안내. 예전 그림이 있으면 그대로 쓰여요
 *  - 친구 탭 맨 위 상자와 화자 고르기 팝업의 "내 캐릭터 만들기"에서 들어와요
 */

class ProfileCharacterViewModel(private val repo: LologRepository) : ViewModel() {
    data class State(
        val loading: Boolean = true,
        val character: ProfileCharacter? = null,
        val busy: Boolean = false,
        val toast: String? = null,
        val loadFailed: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    private var poll: Job? = null

    fun load() = viewModelScope.launch {
        runCatching { repo.profile() }
            .onSuccess { p -> _state.update { it.copy(loading = false, loadFailed = false, character = p.character) }; watch() }
            .onFailure { _state.update { it.copy(loading = false, loadFailed = it.character == null) } }
    }

    // 그리는 중이면 화면에 있는 동안 5초마다 다시 봐요. 푸시가 안 와도 결과가 보이게요
    private fun watch() {
        if (_state.value.character?.status != ProfileCharacterStatus.PROCESSING || poll?.isActive == true) return
        poll = viewModelScope.launch {
            while (isActive && _state.value.character?.status == ProfileCharacterStatus.PROCESSING) {
                delay(POLL_MS)
                runCatching { repo.profile() }.onSuccess { p -> _state.update { it.copy(character = p.character) } }
            }
        }
    }

    fun upload(prepare: suspend () -> ProfilePhoto.Result) = viewModelScope.launch {
        if (_state.value.busy) return@launch
        _state.update { it.copy(busy = true) }
        val photo = runCatching { prepare() }.getOrElse { ProfilePhoto.Result.Unreadable }
        when (photo) {
            is ProfilePhoto.Result.WrongFaces -> _state.update { it.copy(busy = false, toast = ONE_FACE) }
            ProfilePhoto.Result.Unreadable -> _state.update { it.copy(busy = false, toast = "사진을 읽지 못했어요. 다른 사진을 골라 주세요") }
            is ProfilePhoto.Result.Ok -> try {
                val c = repo.uploadProfileCharacter(photo.jpeg)
                _state.update { it.copy(busy = false, character = c, toast = "캐릭터를 그리기 시작했어요. 다 되면 알려 드릴게요") }
                watch()
            } catch (e: LologException) {
                _state.update { it.copy(busy = false, toast = errorText(e)) }
                load()
            }
        }
    }

    fun delete() = viewModelScope.launch {
        _state.update { it.copy(busy = true) }
        try {
            repo.deleteProfileCharacter()
            poll?.cancel()
            _state.update { it.copy(busy = false, character = null, toast = "내 캐릭터를 지웠어요") }
        } catch (e: LologException) {
            _state.update { it.copy(busy = false, toast = errorText(e)) }
        }
    }

    fun toastShown() = _state.update { it.copy(toast = null) }

    companion object {
        /** 명세 문구 그대로 */
        const val ONE_FACE = "얼굴이 한 명만 보이는 사진을 골라 주세요"
        private const val POLL_MS = 5_000L
    }
}

@Composable
fun ProfileCharacterScreen(onBack: () -> Unit) {
    val repo = LocalContainer.current.repository
    val vm = viewModel { ProfileCharacterViewModel(repo) }
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var askDelete by remember { mutableStateOf(false) }

    // 푸시(완성·실패)가 오면 이 화면도 바로 새로 불러요
    LaunchedEffect(Unit) { vm.load() }
    LaunchedEffect(Unit) { repo.pushEvents.collect { vm.load() } }
    LaunchedEffect(state.toast) {
        val msg = state.toast ?: return@LaunchedEffect
        snackbar.showSnackbar(msg)
        vm.toastShown()
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) vm.upload { ProfilePhoto.prepare(context, uri) }
    }
    fun pick() = picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))

    val c = state.character
    val processing = c?.status == ProfileCharacterStatus.PROCESSING
    Scaffold(
        containerColor = LologColors.Paper,
        snackbarHost = { LologSnackbarHost(snackbar) },
        topBar = { LologTopBar("내 캐릭터", onBack = onBack) },
    ) { pad ->
        when {
            state.loading -> SkeletonList(Modifier.padding(pad), count = 2)
            state.loadFailed -> ListErrorState(onRetry = { vm.load() }, modifier = Modifier.padding(pad), title = "불러오지 못했어요")
            else -> Column(
                Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 그림(없으면 사람 아이콘). 그리는 중에는 위에 점 세 개
                Box(Modifier.size(180.dp).clip(CircleShape).background(LologColors.Canvas), contentAlignment = Alignment.Center) {
                    if (c?.thumbnailUrl != null) {
                        AsyncImage(c.thumbnailUrl, contentDescription = "내 캐릭터", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    } else {
                        Icon(Icons.Outlined.Person, null, tint = LologColors.Muted, modifier = Modifier.size(72.dp))
                    }
                    if (processing) {
                        Box(Modifier.matchParentSize().background(LologColors.Paper.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                            TypingDots()
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
                Text(
                    when (c?.status) {
                        ProfileCharacterStatus.READY -> "내 캐릭터가 있어요"
                        ProfileCharacterStatus.PROCESSING -> "캐릭터를 그리고 있어요"
                        ProfileCharacterStatus.FAILED -> "캐릭터를 만들지 못했어요"
                        else -> "내 캐릭터를 만들어 보세요"
                    },
                    fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = LologColors.Ink,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    when (c?.status) {
                        ProfileCharacterStatus.PROCESSING ->
                            "몇 분 걸려요. 다 되면 알림으로 알려 드릴게요." + if (c.thumbnailUrl != null) " 그동안은 지금 그림을 그대로 써요." else ""
                        ProfileCharacterStatus.FAILED ->
                            "다른 사진으로 다시 만들어 볼 수 있어요." + if (c.thumbnailUrl != null) " 지금 그림은 그대로 써요." else ""
                        else -> "사진 한 장으로 만화 그림체의 내 캐릭터를 만들어요."
                    },
                    fontSize = 14.sp, color = LologColors.Muted, textAlign = TextAlign.Center, lineHeight = 20.sp,
                )
                Spacer(Modifier.height(16.dp))
                // 명세 안내 문구 "친구가 내 캐릭터를 만화에 쓸 수 있어요" + 사진을 지운다는 안내
                Column(
                    Modifier.fillMaxWidth().softCard().padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Bullet("친구가 내 캐릭터를 만화에 쓸 수 있어요")
                    Bullet("얼굴이 한 명만 보이는 사진을 골라 주세요")
                    Bullet("사진은 그림으로 바꾼 뒤 바로 지워요")
                    Bullet("하루에 3번까지 만들 수 있어요")
                }
                if (c?.status == ProfileCharacterStatus.FAILED) {
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.ErrorOutline, null, tint = LologColors.Rec, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("얼굴이 잘 보이는 정면 사진이면 더 잘 그려져요", fontSize = 12.sp, color = LologColors.Rec)
                    }
                }
                Spacer(Modifier.height(24.dp))
                PrimaryButton(
                    when {
                        processing -> "그리는 중…"
                        c == null -> "사진 고르기"
                        else -> "다른 사진으로 다시 만들기"
                    },
                    onClick = ::pick, enabled = !processing && !state.busy, loading = state.busy,
                )
                if (c != null) {
                    Spacer(Modifier.height(8.dp))
                    GhostButton("내 캐릭터 지우기", { askDelete = true }, enabled = !state.busy)
                }
            }
        }
    }

    if (askDelete) {
        LologDialog(
            title = "내 캐릭터를 지울까요?",
            body = "내 카드와 친구들 카드의 화자에 붙어 있던 내 캐릭터도 빠져요. 이미 만든 만화는 그대로예요.",
            confirmText = "지우기",
            danger = true,
            loading = state.busy,
            onConfirm = { askDelete = false; vm.delete() },
            onDismiss = { askDelete = false },
        )
    }
}

@Composable
private fun Bullet(text: String) {
    Row {
        Text("·", fontSize = 13.sp, color = LologColors.Muted, modifier = Modifier.width(12.dp))
        Text(text, fontSize = 13.sp, color = LologColors.Ink)
    }
}
