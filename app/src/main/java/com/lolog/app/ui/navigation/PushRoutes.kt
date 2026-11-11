package com.lolog.app.ui.navigation

import androidx.navigation.NavHostController
import com.lolog.app.data.model.PushPayload
import com.lolog.app.data.model.PushType

/**
 * 알림을 눌렀을 때 갈 화면 (협의 2-2 기본안).
 * 알림창의 푸시를 눌렀을 때(LologNavHost)와 알림함에서 알림 줄을 눌렀을 때(InboxScreen)가 같이 써요.
 */
fun NavHostController.openPush(p: PushPayload) {
    when (p.type) {
        // 명세: 새 카드 → 보관함 새로고침. (제안한 cardIds가 오고 1개면 그 카드로)
        PushType.CARDS_CREATED ->
            if (p.cardIds.size == 1) navigate(Routes.card(p.cardIds.first())) else goTab(Routes.LIBRARY)
        PushType.COMIC_DONE -> p.comicId?.let { navigate(Routes.comic(it)) } ?: goTab(Routes.COMICS)
        // 실패 → 그 만화 화면. "만들지 못했어요" 안내와 "다시 시도" 버튼이 거기 있어요
        PushType.COMIC_FAILED -> p.comicId?.let { navigate(Routes.comic(it)) } ?: goTab(Routes.COMICS)
        // 내 캐릭터 완성·실패 → 내 캐릭터 화면(api-dto 푸시 표: 프로필 새로고침)
        PushType.PROFILE_CHARACTER_READY, PushType.PROFILE_CHARACTER_FAILED ->
            navigate(Routes.PROFILE_CHARACTER) { launchSingleTop = true }
        // 친구 요청 → 받은 요청 화면(friends.md 3장 "친구 화면의 받은 요청")
        PushType.FRIEND_REQUEST -> navigate(Routes.FRIEND_REQUESTS) { launchSingleTop = true }
    }
}
