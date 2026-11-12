package com.lolog.app.ui.screens.text

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.outlined.RssFeed
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lolog.app.data.model.BLOG_CATEGORY_MAX
import com.lolog.app.data.model.Blog
import com.lolog.app.data.model.BlogCheck
import com.lolog.app.data.model.BlogPost
import com.lolog.app.data.model.ErrorCodes
import com.lolog.app.data.model.TextStatus
import com.lolog.app.data.repo.LologException
import com.lolog.app.data.repo.LologRepository
import com.lolog.app.ui.components.*
import com.lolog.app.ui.theme.LologColors
import com.lolog.app.ui.theme.LologShape
import com.lolog.app.util.relativeTime
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/*
 * 글로 만들기 › 블로그 탭(글로 카드 만들기 시안 6·7·8번, texts.md 4장 초안)
 *  - 연결한 블로그가 없으면: 주소 + [확인] → 블로그 줄 · 가져올 카테고리(피드 목록 + 직접 적기) · [블로그 연결]
 *  - 있으면: 블로그 카드(여럿이면 위아래로 쌓고 고른 것만 코랄 테두리) + 고른 블로그의 가져온 글 목록
 *    ⋯ → 카테고리 바꾸기 · 연결 끊기. 아래 [+ 블로그 더 연결하기]는 같은 연결 칸을 아래 창으로
 *  - 블로그 목록·연결 끊기 주소는 명세에 없어서 저장소 함수만 만들어 뒀어요. 실서버는 아직 "준비 중"
 */

/** 연결 칸(주소 확인 → 카테고리 고르기)의 상태. 빈 화면과 "더 연결하기" 창이 같이 써요 */
data class BlogForm(
    val url: String = "",
    val checking: Boolean = false,
    val check: BlogCheck? = null,
    /** 확인 실패 문구. 있으면 칸이 빨간 테두리 */
    val checkError: String? = null,
    /** 피드에서 나온 카테고리 + 직접 적은 것 */
    val options: List<String> = emptyList(),
    val picked: Set<String> = emptySet(),
    val custom: String = "",
    val customError: String? = null,
    val connecting: Boolean = false,
)

class TextBlogViewModel(private val repo: LologRepository) : ViewModel() {
    data class State(
        val blogs: List<Blog> = emptyList(),
        val loading: Boolean = true,
        val loadFailed: Boolean = false,
        val selectedId: Long? = null,
        val posts: List<BlogPost> = emptyList(),
        val postsCursor: String? = null,
        val postsLoading: Boolean = false,
        val form: BlogForm = BlogForm(),
        /** 카테고리 바꾸기 창: 고르는 중인 것(피드 목록은 확인 API로 다시 받아요) */
        val editing: BlogForm? = null,
        val busy: Boolean = false,
        val toast: String? = null,
        /** 연결했으면 true. "더 연결하기" 창을 닫아요 */
        val connected: Boolean = false,
    ) {
        val selected: Blog? get() = blogs.firstOrNull { it.id == selectedId } ?: blogs.firstOrNull()
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    /** 블로그 목록. 실서버는 아직 API가 없어서(NOT_READY) 빈 목록으로 보여 줘요 */
    fun load(select: Long? = null) = viewModelScope.launch {
        _state.update { it.copy(loading = it.blogs.isEmpty(), loadFailed = false) }
        try {
            val blogs = repo.blogs()
            _state.update { it.copy(blogs = blogs, loading = false, selectedId = select ?: it.selectedId?.takeIf { id -> blogs.any { b -> b.id == id } }) }
            _state.value.selected?.let { loadPosts(it.id) }
        } catch (e: LologException) {
            _state.update { if (e.code == ErrorCodes.NOT_READY) it.copy(blogs = emptyList(), loading = false) else it.copy(loading = false, loadFailed = true) }
        }
    }

    fun select(id: Long) {
        if (id == _state.value.selected?.id) return
        _state.update { it.copy(selectedId = id, posts = emptyList(), postsCursor = null) }
        loadPosts(id)
    }

    private var postsJob: Job? = null

    /** GET /api/blogs/{id}/posts. [more]면 다음 쪽을 붙여요 */
    fun loadPosts(id: Long, more: Boolean = false) {
        postsJob?.cancel()
        val cursor = if (more) _state.value.postsCursor ?: return else null
        _state.update { it.copy(postsLoading = true) }
        postsJob = viewModelScope.launch {
            try {
                val page = repo.blogPosts(id, cursor)
                _state.update { if (it.selected?.id != id) it else it.copy(posts = if (more) it.posts + page.posts else page.posts, postsCursor = page.nextCursor, postsLoading = false) }
            } catch (e: LologException) {
                _state.update { it.copy(postsLoading = false, toast = textErrorText(e)) }
            }
        }
    }

    // ── 연결 칸 ──

    private fun form(edit: (BlogForm) -> BlogForm) = _state.update { it.copy(form = edit(it.form)) }

    /** 주소를 바꾸면 확인 결과를 지워요 */
    fun setUrl(v: String) = form { BlogForm(url = v) }

    fun resetForm() = _state.update { it.copy(form = BlogForm(), connected = false) }

    /** POST /api/blogs/check. 실패하면 칸 아래 빨간 글 */
    fun check() {
        val url = _state.value.form.url.trim()
        if (url.isEmpty() || _state.value.form.checking) return
        form { it.copy(checking = true, checkError = null) }
        viewModelScope.launch {
            try {
                val c = repo.checkBlog(url)
                form { it.copy(checking = false, check = c, options = c.categories.distinct(), picked = emptySet()) }
            } catch (e: LologException) {
                form { it.copy(checking = false, check = null, checkError = textErrorText(e)) }
            }
        }
    }

    fun toggle(category: String) = form { it.toggled(category) }
    fun setCustom(v: String) = form { it.copy(custom = v, customError = null) }
    fun addCustom() = form { it.withCustom() }

    /** POST /api/blogs → 새 블로그를 골라 목록을 다시 받아요 */
    fun connect() {
        val f = _state.value.form
        if (f.check == null || f.connecting) return
        form { it.copy(connecting = true) }
        viewModelScope.launch {
            try {
                val blog = repo.addBlog(f.url.trim(), f.options.filter { it in f.picked })
                _state.update { it.copy(form = BlogForm(), connected = true) }
                load(select = blog.id)
            } catch (e: LologException) {
                form { it.copy(connecting = false) }
                _state.update { it.copy(toast = textErrorText(e)) }
            }
        }
    }

    // ── 카테고리 바꾸기 · 연결 끊기 ──

    private fun editing(edit: (BlogForm) -> BlogForm) = _state.update { s -> s.copy(editing = s.editing?.let(edit)) }

    /** 지금 고른 것으로 시작하고, 피드 목록을 다시 받아 선택지를 채워요(못 받으면 고른 것만) */
    fun startEdit(blog: Blog) {
        _state.update { it.copy(editing = BlogForm(url = blog.siteUrl, checking = true, options = blog.categories, picked = blog.categories.toSet())) }
        viewModelScope.launch {
            val feed = runCatching { repo.checkBlog(blog.siteUrl).categories }.getOrDefault(emptyList())
            editing { it.copy(checking = false, options = (it.options + feed).distinct()) }
        }
    }

    fun toggleEdit(category: String) = editing { it.toggled(category) }
    fun setEditCustom(v: String) = editing { it.copy(custom = v, customError = null) }
    fun addEditCustom() = editing { it.withCustom() }
    fun cancelEdit() = _state.update { it.copy(editing = null) }

    /** PATCH /api/blogs/{id} */
    fun saveEdit(id: Long) {
        val e = _state.value.editing ?: return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val blog = repo.updateBlogCategories(id, e.options.filter { it in e.picked })
                _state.update { s -> s.copy(busy = false, editing = null, blogs = s.blogs.map { if (it.id == id) blog else it }, toast = "카테고리를 바꿨어요") }
            } catch (ex: LologException) {
                _state.update { it.copy(busy = false, toast = textErrorText(ex)) }
            }
        }
    }

    /** 연결 끊기. 이미 만든 카드는 남아요 */
    fun delete(id: Long, onDone: () -> Unit) {
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                repo.deleteBlog(id)
                _state.update { s -> s.copy(busy = false, blogs = s.blogs.filter { it.id != id }, selectedId = null, posts = emptyList(), postsCursor = null, toast = "블로그 연결을 끊었어요") }
                onDone()
                _state.value.selected?.let { loadPosts(it.id) }
            } catch (e: LologException) {
                _state.update { it.copy(busy = false, toast = textErrorText(e)) }
            }
        }
    }

    fun toastShown() = _state.update { it.copy(toast = null) }
}

private fun BlogForm.toggled(c: String) = copy(picked = if (c in picked) picked - c else picked + c)

/** 직접 적은 카테고리를 알약으로 더해요(1~30자, 같은 이름은 막기). 더하면 바로 골라져요 */
private fun BlogForm.withCustom(): BlogForm {
    val name = custom.trim()
    return when {
        name.isEmpty() || name.length > BLOG_CATEGORY_MAX -> copy(customError = "1~${BLOG_CATEGORY_MAX}자로 적어 주세요")
        options.any { it.equals(name, ignoreCase = true) } -> copy(customError = "이미 있는 카테고리예요")
        else -> copy(options = options + name, picked = picked + name, custom = "", customError = null)
    }
}

private fun platformLabel(platform: String) = when (platform) {
    "NAVER" -> "네이버 블로그"
    "TISTORY" -> "티스토리"
    else -> "블로그"
}

/** 블로그 탭 본문. [vm]은 쓰기 화면이 만들어 넘겨요(띠도 쓰기 화면이 보여 줘요) */
@Composable
fun BlogTab(vm: TextBlogViewModel, onCard: (Long) -> Unit, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsState()
    var menuFor by remember { mutableStateOf<Blog?>(null) }
    var editFor by remember { mutableStateOf<Blog?>(null) }
    var askDelete by remember { mutableStateOf<Blog?>(null) }
    var addSheet by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.load() }
    LaunchedEffect(state.connected) { if (state.connected) addSheet = false }

    Column(modifier.fillMaxWidth()) {
        when {
            state.loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { TypingDots() }
            state.loadFailed -> ListErrorState(onRetry = { vm.load() }, modifier = Modifier.weight(1f), title = "블로그를 불러오지 못했어요")
            state.blogs.isEmpty() -> {
                // 6·7번: 연결 칸이 곧 화면
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
                    ConnectForm(state.form, vm, showEmpty = true)
                }
                BottomButton { PrimaryButton("블로그 연결", vm::connect, enabled = state.form.check != null, loading = state.form.connecting) }
            }
            else -> {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
                    // 8번: 블로그 카드(여럿이면 쌓기)
                    state.blogs.forEach { blog ->
                        BlogCard(blog, selected = state.blogs.size > 1 && blog.id == state.selected?.id, onClick = { vm.select(blog.id) }, onMenu = { menuFor = blog })
                        Spacer(Modifier.height(8.dp))
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("가져온 글", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = LologColors.Ink)
                        Spacer(Modifier.weight(1f))
                        Text("새 글은 저절로 카드가 돼요", fontSize = 11.sp, color = LologColors.Muted)
                    }
                    Spacer(Modifier.height(6.dp))
                    when {
                        state.posts.isEmpty() && state.postsLoading -> LoadMoreDots()
                        state.posts.isEmpty() -> Text(
                            "아직 가져온 글이 없어요. 고른 카테고리에 새 글을 쓰면 30분 안에 가져와요.",
                            fontSize = 12.5.sp, color = LologColors.Muted, modifier = Modifier.padding(vertical = 16.dp),
                        )
                        else -> {
                            state.posts.forEach { p ->
                                PostRow(p, onClick = p.cardId?.takeIf { p.status == TextStatus.DONE }?.let { id -> { onCard(id) } })
                            }
                            when {
                                state.postsLoading -> LoadMoreDots()
                                state.postsCursor != null -> Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                                    SmallPillButton("더보기", { state.selected?.let { vm.loadPosts(it.id, more = true) } })
                                }
                            }
                        }
                    }
                }
                BottomButton { GhostButton("+ 블로그 더 연결하기", { vm.resetForm(); addSheet = true }) }
            }
        }
    }

    if (addSheet) {
        LologSheet(onDismiss = { addSheet = false }) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
                ConnectForm(state.form, vm, showEmpty = false)
                Spacer(Modifier.height(14.dp))
                PrimaryButton("블로그 연결", vm::connect, enabled = state.form.check != null, loading = state.form.connecting)
            }
        }
    }

    menuFor?.let { blog ->
        LologSheet(onDismiss = { menuFor = null }) {
            Column(Modifier.padding(horizontal = 8.dp).padding(bottom = 24.dp)) {
                Text(blog.title ?: blog.siteUrl, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(12.dp))
                SheetRow("카테고리 바꾸기", LologColors.Ink) { menuFor = null; editFor = blog; vm.startEdit(blog) }
                SheetRow("연결 끊기", LologColors.Rec) { menuFor = null; askDelete = blog }
            }
        }
    }

    editFor?.let { blog ->
        val edit = state.editing
        LologSheet(onDismiss = { editFor = null; vm.cancelEdit() }) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
                Text("카테고리 바꾸기", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(bottom = 12.dp))
                if (edit != null) {
                    CategoryPicker(edit, vm::toggleEdit, vm::setEditCustom, vm::addEditCustom)
                    if (edit.checking) LoadMoreDots()
                }
                Spacer(Modifier.height(14.dp))
                PrimaryButton("저장", { vm.saveEdit(blog.id) }, enabled = edit != null, loading = state.busy)
            }
        }
        // 저장하면 창을 닫아요
        LaunchedEffect(edit) { if (edit == null && !state.busy) editFor = null }
    }

    askDelete?.let { blog ->
        LologDialog(
            title = "블로그 연결을 끊을까요?",
            body = "이미 만든 카드는 남아요",
            confirmText = "연결 끊기",
            danger = true,
            loading = state.busy,
            onConfirm = { vm.delete(blog.id) { askDelete = null } },
            onDismiss = { if (!state.busy) askDelete = null },
        )
    }
}

@Composable
private fun BottomButton(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 16.dp, vertical = 12.dp)) { content() }
}

/**
 * 연결 칸(6·7번): "내 블로그 주소" · 주소 + [확인] · 안내.
 * 확인 전이면 가운데 빈 안내([showEmpty]일 때), 확인 뒤에는 블로그 줄 · 가져올 카테고리 · RSS 안내
 */
@Composable
private fun ConnectForm(form: BlogForm, vm: TextBlogViewModel, showEmpty: Boolean) {
    Text("내 블로그 주소", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = LologColors.Ink)
    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        InputBox(form.url, vm::setUrl, "blog.naver.com/아이디", Modifier.weight(1f), enabled = !form.connecting, error = form.checkError != null)
        Spacer(Modifier.width(8.dp))
        // 작은 코랄 버튼 "확인"
        Box(
            Modifier.height(46.dp).clip(LologShape.sm)
                .background(if (form.url.isNotBlank()) LologColors.Accent else LologColors.Canvas)
                .clickable(enabled = form.url.isNotBlank() && !form.checking && !form.connecting, onClick = vm::check)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (form.checking) TypingDots(color = LologColors.Paper, dot = 5.dp)
            else Text("확인", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (form.url.isNotBlank()) LologColors.Paper else LologColors.Muted)
        }
    }
    Spacer(Modifier.height(6.dp))
    if (form.checkError != null) Text(form.checkError, fontSize = 12.sp, color = LologColors.Rec, modifier = Modifier.padding(start = 2.dp))
    else Text("네이버 · 티스토리 블로그 주소를 넣고 \"확인\"을 눌러 주세요.", fontSize = 12.sp, color = LologColors.Muted, modifier = Modifier.padding(start = 2.dp))

    val check = form.check
    if (check == null) {
        if (showEmpty) {
            Column(Modifier.fillMaxWidth().padding(top = 72.dp, bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(52.dp).clip(CircleShape).border(1.dp, LologColors.Line, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.RssFeed, null, tint = LologColors.Muted, modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.height(12.dp))
                Text("연결한 블로그가 없어요", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = LologColors.Ink)
                Spacer(Modifier.height(6.dp))
                Text(
                    "블로그를 연결하면, 고른 카테고리의 새 글이 저절로 이야기 카드가 돼요.",
                    fontSize = 12.5.sp, lineHeight = 19.sp, color = LologColors.Muted, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
        }
        return
    }
    Spacer(Modifier.height(14.dp))
    // 확인 응답에는 블로그 이름이 없어서 주소를 보여 줘요
    Row(
        Modifier.fillMaxWidth().clip(LologShape.sm).border(1.dp, LologColors.Line, LologShape.sm).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlatformDot(check.platform)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(form.url.trim(), fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${platformLabel(check.platform)} · RSS 찾음", fontSize = 11.5.sp, color = LologColors.Muted)
        }
    }
    Spacer(Modifier.height(16.dp))
    CategoryPicker(form, vm::toggle, vm::setCustom, vm::addCustom)
    Spacer(Modifier.height(14.dp))
    InfoBox("30분마다 새 글을 확인해요. 링크의 글을 긁지 않고 RSS에 담긴 글만 써요.\n내 블로그를 연결해 주세요.")
}

/** "가져올 카테고리" + 알약 여러 개 고르기 + 직접 적기 칸 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryPicker(form: BlogForm, onToggle: (String) -> Unit, onCustom: (String) -> Unit, onAdd: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("가져올 카테고리", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = LologColors.Ink)
        Spacer(Modifier.weight(1f))
        Text("안 고르면 전부 가져와요", fontSize = 11.sp, color = LologColors.Muted)
    }
    Spacer(Modifier.height(8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        form.options.forEach { c -> CategoryPill(c, c in form.picked) { onToggle(c) } }
    }
    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        InputBox(form.custom, onCustom, "목록에 없으면 직접 적기", Modifier.weight(1f), error = form.customError != null)
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.height(46.dp).clip(LologShape.sm).background(LologColors.Canvas)
                .clickable(enabled = form.custom.isNotBlank(), onClick = onAdd).padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) { Text("더하기", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (form.custom.isNotBlank()) LologColors.Ink else LologColors.Muted) }
    }
    form.customError?.let { Text(it, fontSize = 12.sp, color = LologColors.Rec, modifier = Modifier.padding(start = 2.dp, top = 4.dp)) }
}

/** 카테고리 알약. 고르면 코랄 테두리 · 연한 코랄 바탕 · ✓ */
@Composable
private fun CategoryPill(text: String, on: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.height(32.dp).clip(LologShape.pill)
            .background(if (on) LologColors.RecSoft else LologColors.Paper)
            .border(if (on) 1.5.dp else 1.dp, if (on) LologColors.Accent else LologColors.Line, LologShape.pill)
            .clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (on) {
            Icon(Icons.Filled.Check, null, tint = LologColors.Accent, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(3.dp))
        }
        Text(text, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = if (on) LologColors.Accent else LologColors.Ink)
    }
}

/** 플랫폼 동그라미: 네이버 N(초록), 티스토리 T(코랄), 그 밖 B */
@Composable
private fun PlatformDot(platform: String) {
    val (label, color) = when (platform) {
        "NAVER" -> "N" to LologColors.Naver
        "TISTORY" -> "T" to LologColors.Accent
        else -> "B" to LologColors.Muted
    }
    Box(Modifier.size(32.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = LologColors.Paper)
    }
}

/** 연결한 블로그 카드(8번): 플랫폼 · 이름 · "카테고리: 여행 · 일상 · 30분마다" · ⋯ */
@Composable
private fun BlogCard(blog: Blog, selected: Boolean, onClick: () -> Unit, onMenu: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().softCard(LologShape.sm)
            .then(if (selected) Modifier.border(1.5.dp, LologColors.Accent, LologShape.sm) else Modifier)
            .clickable(onClick = onClick).padding(start = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlatformDot(blog.platform)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(blog.title ?: blog.siteUrl, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val cats = blog.categories.ifEmpty { listOf("전부") }.joinToString(" · ")
            Text("카테고리: $cats · 30분마다", fontSize = 11.5.sp, color = LologColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onMenu) { Icon(Icons.Filled.MoreHoriz, "블로그 메뉴", tint = LologColors.Muted) }
    }
}

/** 가져온 글 한 줄: "여행 · 1시간 전" · 제목 · 오른쪽 상태 알약. [onClick]이 있으면(카드 만듦) 누르면 카드 */
@Composable
private fun PostRow(post: BlogPost, onClick: (() -> Unit)?) {
    Column {
        Row(
            Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                val meta = listOfNotNull(post.category, relativeTime(post.publishedAt).ifBlank { null }).joinToString(" · ")
                if (meta.isNotEmpty()) Text(meta, fontSize = 11.sp, color = LologColors.Muted)
                Text(post.title, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            PostStatus(post.status)
        }
        androidx.compose.material3.HorizontalDivider(thickness = 1.dp, color = LologColors.Line)
    }
}

/** 상태 알약: 카드 만듦(초록) · 만드는 중(코랄) · 실패(빨강) */
@Composable
private fun PostStatus(status: TextStatus) {
    val (label, bg, fg) = when (status) {
        TextStatus.DONE -> Triple("카드 만듦", LologColors.MemorySoft, LologColors.MemoryText)
        TextStatus.QUEUED -> Triple("만드는 중", LologColors.AccentSoft, LologColors.Accent)
        TextStatus.FAILED -> Triple("실패", LologColors.RecSoft, LologColors.Rec)
    }
    Text(
        label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = fg,
        modifier = Modifier.clip(LologShape.pill).background(bg).padding(horizontal = 9.dp, vertical = 4.dp),
    )
}

/** 아래 창의 메뉴 한 줄 */
@Composable
private fun SheetRow(text: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Text(
        text, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = color,
        modifier = Modifier.fillMaxWidth().clip(LologShape.sm).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 14.dp),
    )
}
