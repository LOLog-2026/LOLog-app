package com.lolog.app.ui.badges

import com.lolog.app.data.model.BadgeKind
import com.lolog.app.data.model.Badges
import com.lolog.app.data.model.PushPayload
import com.lolog.app.data.repo.LologRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 앱 안 새 소식 점을 한 곳에서 들고 있어요(LOLog-docs spec/inbox.md 4장).
 *  - 탭을 오갈 때·앱을 켤 때 [refresh]로 GET /api/me/badges
 *  - 그 화면을 열면 [seen]: 점을 바로 끄고 POST /api/me/badges/{kind}/read, 그다음 서버 값으로 다시 맞춰요
 *    (친구 요청·종 버튼은 서버가 받은 요청 수·안 읽은 알림으로 정해서, 남아 있으면 다시 켜져요)
 *  - 새 푸시가 오면 그 기능 점과 종 버튼 점을 바로 켜요
 * 실패하면(인터넷 끊김 등) 지금 점을 그대로 둬요.
 */
class BadgeStore(
    private val load: suspend () -> Badges,
    private val read: suspend (BadgeKind) -> Unit,
    pushes: Flow<PushPayload>,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
) {
    constructor(repo: LologRepository) : this(repo::badges, repo::readBadge, repo.pushEvents)

    private val _badges = MutableStateFlow(Badges())
    val badges: StateFlow<Badges> = _badges

    init {
        scope.launch {
            pushes.collect { p -> _badges.update { it.with(BadgeKind.of(p.type), true).copy(inbox = true) } }
        }
    }

    fun refresh() {
        scope.launch { runCatching { load() }.onSuccess { _badges.value = it } }
    }

    fun seen(kind: BadgeKind) {
        _badges.update { it.with(kind, false) }
        scope.launch {
            runCatching { read(kind) }
            runCatching { load() }.onSuccess { _badges.value = it }
        }
    }

    /** 로그아웃하면 다 꺼요 */
    fun clear() {
        _badges.value = Badges()
    }
}
