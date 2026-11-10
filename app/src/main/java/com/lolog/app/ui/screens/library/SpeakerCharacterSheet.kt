package com.lolog.app.ui.screens.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.lolog.app.data.model.*
import com.lolog.app.data.repo.LologRepository
import com.lolog.app.ui.components.*
import com.lolog.app.ui.theme.LologColors
import com.lolog.app.ui.theme.LologShape
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope

/*
 * 화자 고치기 팝업 (프로필캐릭터-시안-1111 11~15, LOLog-docs spec/characters.md 1장)
 *  - 위: "○○은 누구랑 닮았어요?" + 탭 두 개 "기본" / "친구 프로필"
 *  - 친구 프로필: 맨 앞 "나", 그 뒤 친구 전부. 모두 똑같이 보여요(흐리게 하거나 숨기지 않음)
 *    고르면 닉네임이 이름 칸에 들어가고, 그림이 있으면 그 사람의 PROFILE이 골라져요
 *    그림이 없으면 회색 안내 + 기본 화자 가로 줄 + "AI에게 맡기기". 나는 "내 캐릭터 만들기"(이번엔 자리만)
 *  - 기본: 나이대·성별 거르기 + 격자. 이름은 그대로 둬요
 *  - 맨 아래 "그림 떼기"(지금 그림이 붙어 있을 때만)
 *  - "AI에게 맡기기"·그냥 닫기 = 그림은 그대로 두고 이름만 저장
 * 저장(PATCH)은 부르는 화면이 해요: 카드 보기는 바로, 카드 고치기는 "저장" 때 한꺼번에.
 */

/** 팝업에서 고른 결과. [characterChanged]가 false면 그림은 그대로예요(character는 보지 않아요) */
data class SpeakerPick(val name: String?, val character: SpeakerCharacter?, val characterChanged: Boolean)

/** 팝업이 쓰는 데이터. 기본 화자 목록은 처음 열 때 한 번 받아 들고 있어요 */
class SpeakerCharacterViewModel(private val repo: LologRepository) : ViewModel() {
    data class State(
        val defaults: List<DefaultCharacter>? = null,
        val defaultsFailed: Boolean = false,
        val me: Profile? = null,
        val friends: List<Friend> = emptyList(),
        val loading: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    private var defaultsAt = 0L

    /** 팝업을 열 때마다 불러요. 나·친구는 매번 새로, 기본 화자는 처음 한 번(썸네일 주소가 10분이라 9분 지나면 다시) */
    fun open() = viewModelScope.launch {
        if (_state.value.loading) return@launch
        _state.update { it.copy(loading = true) }
        coroutineScope {
            val stale = _state.value.defaults == null || System.currentTimeMillis() - defaultsAt > DEFAULTS_TTL_MS
            val defaults = if (stale) async { runCatching { repo.defaultCharacters() } } else null
            val me = async { runCatching { repo.profile() }.getOrNull() }
            val friends = async { runCatching { repo.friends() }.getOrNull() }
            val d = defaults?.await()
            if (d?.isSuccess == true) defaultsAt = System.currentTimeMillis()
            val m = me.await()
            val f = friends.await()
            _state.update {
                it.copy(
                    defaults = d?.getOrNull() ?: it.defaults,
                    defaultsFailed = d?.isFailure == true && it.defaults == null,
                    me = m ?: it.me,
                    friends = f ?: it.friends,
                    loading = false,
                )
            }
        }
    }

    private companion object {
        const val DEFAULTS_TTL_MS = 9 * 60 * 1000L
    }
}

/** 친구 프로필 탭의 한 사람(나 또는 친구) */
private data class Person(val id: String?, val nickname: String, val thumbnailUrl: String?, val isMe: Boolean) {
    /** 화자에 붙일 수 있는 그림. 회원 ID를 모르면(실서버의 나) 붙일 수 없어요 */
    val character: SpeakerCharacter?
        get() = if (id != null && thumbnailUrl != null) SpeakerCharacter(CharacterType.PROFILE, id, nickname, thumbnailUrl) else null
    val key: String get() = if (isMe) "me" else "friend-$id"
}

private enum class SheetTab(val label: String) { DEFAULT("기본"), FRIENDS("친구 프로필") }

@Composable
fun SpeakerCharacterSheet(
    speaker: Speaker,
    /** 이름이 없을 때 부르는 이름(화자1·2…) */
    placeholder: String,
    data: SpeakerCharacterViewModel.State,
    onRetry: () -> Unit,
    onClose: (SpeakerPick) -> Unit,
) {
    val current = speaker.character
    var name by remember { mutableStateOf(speaker.name.orEmpty()) }
    var tab by remember { mutableStateOf(if (current?.type == CharacterType.DEFAULT) SheetTab.DEFAULT else SheetTab.FRIENDS) }
    var person by remember { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf(current?.takeIf { it.type == CharacterType.DEFAULT }?.id) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val people = remember(data.me, data.friends) {
        // TODO 명세에 내 userId 필요: 실서버에서는 me.userId가 null이라 "나"에 PROFILE을 붙이지 않아요
        val me = data.me?.let { Person(it.userId, it.nickname ?: "나", it.character?.thumbnailUrl, isMe = true) }
        listOfNotNull(me) + data.friends.map { Person(it.userId.toString(), it.nickname, it.thumbnailUrl, isMe = false) }
    }
    // 처음 열 때: 지금 붙은 PROFILE의 사람을 골라 둬요
    LaunchedEffect(people) {
        if (person == null && current?.type == CharacterType.PROFILE) person = people.firstOrNull { it.id == current.id }?.key
    }
    val chosen = people.firstOrNull { it.key == person }

    val cleanName = name.trim().take(TextLimits.SPEAKER_NAME).ifBlank { null }
    fun keep() = onClose(SpeakerPick(cleanName, null, characterChanged = false))
    fun attach(c: SpeakerCharacter) = onClose(SpeakerPick(cleanName, c, characterChanged = true))
    fun pickedDefault() = data.defaults?.firstOrNull { it.id == picked }
        ?.let { SpeakerCharacter(CharacterType.DEFAULT, it.id, it.label, it.thumbnailUrl) }

    LologSheet(onDismiss = ::keep) {
        Box {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 20.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val who = cleanName ?: placeholder
                    Text("$who${topicMarker(who)} 누구랑 닮았어요?", fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = ::keep) { Icon(Icons.Default.Close, "닫기", tint = LologColors.Muted) }
                }
                TabRow(
                    selectedTabIndex = tab.ordinal,
                    containerColor = LologColors.Paper,
                    contentColor = LologColors.Ink,
                    indicator = { pos ->
                        TabRowDefaults.SecondaryIndicator(with(TabRowDefaults) { Modifier.tabIndicatorOffset(pos[tab.ordinal]) }, color = LologColors.Accent)
                    },
                    divider = { HorizontalDivider(color = LologColors.Line) },
                ) {
                    SheetTab.entries.forEach { t ->
                        Tab(selected = tab == t, onClick = { tab = t }, selectedContentColor = LologColors.Ink, unselectedContentColor = LologColors.Muted) {
                            Text(t.label, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 10.dp))
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))

                when (tab) {
                    SheetTab.FRIENDS -> FriendsTab(
                        people = people, chosen = chosen, loading = data.loading && data.me == null,
                        name = name, onName = { name = it }, placeholder = placeholder,
                        defaults = data.defaults, picked = picked, onPickDefault = { picked = it },
                        onChoose = { p -> person = p.key; name = p.nickname.take(TextLimits.SPEAKER_NAME); picked = null },
                        onAttach = ::attach, onKeep = ::keep, attachDefault = { pickedDefault()?.let(::attach) },
                        onMakeMine = { scope.launch { snackbar.showSnackbar("곧 만들 수 있어요") } },
                        onGoDefault = { tab = SheetTab.DEFAULT },
                    )
                    SheetTab.DEFAULT -> DefaultTab(
                        defaults = data.defaults, failed = data.defaultsFailed, onRetry = onRetry,
                        name = name, onName = { name = it }, placeholder = placeholder,
                        picked = picked, onPick = { picked = it },
                        onKeep = ::keep, onAttach = { pickedDefault()?.let(::attach) },
                    )
                }

                if (current != null) {
                    TextButton(onClick = { onClose(SpeakerPick(cleanName, null, characterChanged = true)) }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text("그림 떼기", fontSize = 13.sp, color = LologColors.Muted)
                    }
                }
            }
            LologSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
        }
    }
}

// ── 친구 프로필 탭 (시안 12·13·15) ──

@Composable
private fun FriendsTab(
    people: List<Person>,
    chosen: Person?,
    loading: Boolean,
    name: String,
    onName: (String) -> Unit,
    placeholder: String,
    defaults: List<DefaultCharacter>?,
    picked: String?,
    onPickDefault: (String) -> Unit,
    onChoose: (Person) -> Unit,
    onAttach: (SpeakerCharacter) -> Unit,
    onKeep: () -> Unit,
    attachDefault: () -> Unit,
    onMakeMine: () -> Unit,
    onGoDefault: () -> Unit,
) {
    if (chosen == null) {
        Text("나와 친구들이 직접 만든 캐릭터예요. 고르면 이름도 같이 들어가요.", fontSize = 12.sp, color = LologColors.Muted, lineHeight = 18.sp)
        Spacer(Modifier.height(12.dp))
    }
    if (loading) {
        Box(Modifier.fillMaxWidth().height(96.dp), contentAlignment = Alignment.Center) { TypingDots() }
    } else {
        // 4명씩 한 줄
        people.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                row.forEach { p ->
                    PersonCell(p, selected = p.key == chosen?.key, modifier = Modifier.weight(1f)) { onChoose(p) }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
    Spacer(Modifier.height(4.dp))
    NameField(name, onName, placeholder)
    Spacer(Modifier.height(12.dp))

    val pic = chosen?.character
    when {
        // 아직 아무도 안 골랐으면 이름만 고칠 수 있어요
        chosen == null -> GhostButton("AI에게 맡기기", onKeep)
        // 그림 있는 사람: 그 사람의 프로필 캐릭터 (시안 12)
        pic != null -> PrimaryButton("이 그림 붙이기", { onAttach(pic) })
        // 나, 그림 없음 (시안 15)
        chosen.isMe -> {
            GrayNote(
                if (chosen.thumbnailUrl == null) "아직 내 캐릭터가 없어요. 만들면 이 화자에 바로 붙일 수 있어요."
                else "지금은 내 캐릭터를 붙일 수 없어요. 기본 화자에서 고르거나 AI에게 맡겨요.",
            )
            Spacer(Modifier.height(12.dp))
            if (chosen.thumbnailUrl == null) {
                PrimaryButton("내 캐릭터 만들기", onMakeMine)
                Spacer(Modifier.height(8.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton("기본 화자에서 고르기", onGoDefault, Modifier.weight(1f))
                GhostButton("AI에게 맡기기", onKeep, Modifier.weight(1f))
            }
        }
        // 그림 없는 친구 (시안 13)
        else -> {
            GrayNote("${chosen.nickname}님은 아직 캐릭터가 없어요. 아래 기본 화자에서 고르거나 AI에게 맡겨요.")
            Spacer(Modifier.height(12.dp))
            defaults?.let { list ->
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    list.forEach { d -> DefaultThumb(d, picked == d.id, size = 52.dp) { onPickDefault(d.id) } }
                }
                list.firstOrNull { it.id == picked }?.let { PickedLabel(it.label) }
                Spacer(Modifier.height(12.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton("AI에게 맡기기", onKeep, Modifier.weight(1f))
                PrimaryButton("이 그림 붙이기", attachDefault, Modifier.weight(1f), enabled = picked != null)
            }
        }
    }
}

/** 사람 동그라미 + 이름. 고르면 코랄 테두리·코랄 이름, 나는 왼쪽 위에 "나" */
@Composable
private fun PersonCell(p: Person, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clickable(onClick = onClick).padding(vertical = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box {
            Box(
                Modifier.size(60.dp).clip(CircleShape).background(LologColors.Canvas)
                    .border(if (selected) 2.dp else 1.dp, if (selected) LologColors.Accent else LologColors.Line, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (p.thumbnailUrl != null && p.id != null) {
                    AsyncImage(
                        model = characterImage(p.thumbnailUrl, profileCacheKey(p.id)), contentDescription = null,
                        contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(CircleShape),
                    )
                } else if (p.thumbnailUrl != null) {
                    AsyncImage(p.thumbnailUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(CircleShape))
                } else {
                    Icon(Icons.Outlined.Person, null, tint = LologColors.Muted, modifier = Modifier.size(32.dp))
                }
            }
            if (p.isMe) {
                Text(
                    "나", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = LologColors.Paper,
                    modifier = Modifier.align(Alignment.TopStart).clip(LologShape.pill).background(LologColors.Ink).padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            p.nickname, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (selected) LologColors.Accent else LologColors.Ink,
        )
    }
}

// ── 기본 탭 (시안 14) ──

@Composable
private fun DefaultTab(
    defaults: List<DefaultCharacter>?,
    failed: Boolean,
    onRetry: () -> Unit,
    name: String,
    onName: (String) -> Unit,
    placeholder: String,
    picked: String?,
    onPick: (String) -> Unit,
    onKeep: () -> Unit,
    onAttach: () -> Unit,
) {
    var age by remember { mutableStateOf<AgeGroup?>(null) }
    var gender by remember { mutableStateOf<Gender?>(null) }

    NameField(name, onName, placeholder)
    Spacer(Modifier.height(12.dp))
    // 거르기: 전체 · 어린이 · 청년 · 중장년 / 여 · 남 (안경은 넣지 않아요)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterPill("전체", age == null && gender == null) { age = null; gender = null }
        AgeGroup.entries.forEach { a -> FilterPill(a.label, age == a) { age = if (age == a) null else a } }
        Gender.entries.forEach { g -> FilterPill(g.label, gender == g) { gender = if (gender == g) null else g } }
    }
    Spacer(Modifier.height(12.dp))
    when {
        defaults == null && failed -> Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("기본 화자를 불러오지 못했어요", fontSize = 13.sp, color = LologColors.Muted)
            Spacer(Modifier.height(8.dp))
            SmallPillButton("다시 시도", onRetry)
        }
        defaults == null -> Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) { TypingDots() }
        else -> {
            val shown = defaults.filter { (age == null || it.ageGroup == age) && (gender == null || it.gender == gender) }
            shown.chunked(GRID_COLUMNS).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { d ->
                        Box(Modifier.weight(1f).aspectRatio(1f)) { DefaultThumb(d, picked == d.id, size = null) { onPick(d.id) } }
                    }
                    repeat(GRID_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            // 고른 그림 아래 한글 설명 한 줄
            PickedLabel(defaults.firstOrNull { it.id == picked }?.label ?: " ")
        }
    }
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GhostButton("AI에게 맡기기", onKeep, Modifier.weight(1f))
        PrimaryButton("이 그림 붙이기", onAttach, Modifier.weight(1f), enabled = picked != null)
    }
}

/** 시안 14: 한 줄에 6명 */
private const val GRID_COLUMNS = 6

/** 기본 화자 동그라미. 고르면 코랄 테두리. [size]가 null이면 칸을 가득 채워요 */
@Composable
private fun DefaultThumb(d: DefaultCharacter, selected: Boolean, size: Dp?, onClick: () -> Unit) {
    Box(
        (if (size != null) Modifier.size(size) else Modifier.fillMaxSize())
            .clip(CircleShape).background(LologColors.Canvas)
            .border(if (selected) 2.dp else 1.dp, if (selected) LologColors.Accent else LologColors.Line, CircleShape)
            .clickable(onClick = onClick),
    ) {
        d.thumbnailUrl?.let { url ->
            AsyncImage(
                model = characterImage(url, defaultCharacterCacheKey(d.id)), contentDescription = d.label,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(CircleShape),
            )
        }
    }
}

@Composable
private fun PickedLabel(text: String) {
    Text(text, fontSize = 12.sp, color = LologColors.Muted, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
}

/** 이름 칸: "이름  서연   2 / 10" */
@Composable
private fun NameField(value: String, onChange: (String) -> Unit, placeholder: String) {
    Row(
        Modifier.fillMaxWidth().clip(LologShape.sm).border(1.dp, LologColors.Line, LologShape.sm).height(48.dp).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("이름", fontSize = 12.sp, color = LologColors.Muted)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, fontSize = 14.sp, color = LologColors.Muted)
            BasicTextField(
                value, { onChange(it.take(TextLimits.SPEAKER_NAME)) }, singleLine = true,
                textStyle = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, color = LologColors.Ink),
                cursorBrush = SolidColor(LologColors.Accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text("${value.length} / ${TextLimits.SPEAKER_NAME}", fontSize = 11.sp, color = LologColors.Muted)
    }
}

@Composable
private fun GrayNote(text: String) {
    Text(
        text, fontSize = 12.sp, color = LologColors.Muted, lineHeight = 18.sp,
        modifier = Modifier.fillMaxWidth().clip(LologShape.sm).background(LologColors.Canvas).padding(horizontal = 14.dp, vertical = 12.dp),
    )
}

/** 이름 뒤 "은/는": 받침이 있으면 "은". 숫자로 끝나면(화자1) 읽는 소리로 봐요 */
internal fun topicMarker(word: String): String {
    val c = word.lastOrNull() ?: return "는"
    val batchim = when (c) {
        in '가'..'힣' -> (c - '가') % 28 != 0
        in '0'..'9' -> c in "013678"
        else -> false
    }
    return if (batchim) "은" else "는"
}
