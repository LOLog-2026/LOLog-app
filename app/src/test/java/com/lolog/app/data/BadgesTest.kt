package com.lolog.app.data

import com.lolog.app.data.model.BadgeKind
import com.lolog.app.data.model.Badges
import com.lolog.app.data.model.ErrorCodes
import com.lolog.app.data.model.PushPayload
import com.lolog.app.data.model.PushType
import com.lolog.app.data.remote.BadgesDto
import com.lolog.app.data.remote.LologJson
import com.lolog.app.data.repo.LologException
import com.lolog.app.ui.badges.BadgeStore
import com.lolog.app.ui.components.errorText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 새 소식 점(inbox.md 4장), 소셜 연결 끊기·막힌 계정 문구 */
class BadgesTest {

    @Test
    fun `서버 Badges 응답을 읽는다`() {
        val d = LologJson.decodeFromString(
            BadgesDto.serializer(),
            """{"cards":true,"comics":false,"profileCharacter":true,"friends":false,"rooms":true,"inbox":true}""",
        )
        assertTrue(d.cards && d.profileCharacter && d.rooms && d.inbox)
        assertFalse(d.comics || d.friends)
    }

    @Test
    fun `푸시 종류마다 켤 점이 정해져 있다(쇼츠는 컨텐츠 점)`() {
        assertEquals(BadgeKind.CARDS, BadgeKind.of(PushType.CARDS_CREATED))
        assertEquals(BadgeKind.COMICS, BadgeKind.of(PushType.COMIC_FAILED))
        assertEquals(BadgeKind.COMICS, BadgeKind.of(PushType.VIDEO_DONE))
        assertEquals(BadgeKind.PROFILE_CHARACTER, BadgeKind.of(PushType.PROFILE_CHARACTER_READY))
        assertEquals(BadgeKind.FRIENDS, BadgeKind.of(PushType.FRIEND_REQUEST))
        assertEquals(BadgeKind.ROOMS, BadgeKind.of(PushType.ROOM_DISBANDED))
        // 서버 kind 글자
        assertEquals("profileCharacter", BadgeKind.PROFILE_CHARACTER.path)
    }

    @Test
    fun `친구 탭 점은 친구 요청·방·내 캐릭터 중 하나라도 있으면 켜진다`() {
        assertFalse(Badges(cards = true, comics = true, inbox = true).friendsTab)
        assertTrue(Badges(rooms = true).friendsTab)
        assertTrue(Badges().with(BadgeKind.PROFILE_CHARACTER, true).friendsTab)
    }

    @Test
    fun `푸시가 오면 그 점과 종 버튼 점을 켜고, 화면을 열면 끈 뒤 서버 값으로 맞춘다`() {
        val pushes = MutableSharedFlow<PushPayload>(extraBufferCapacity = 4)
        val reads = mutableListOf<BadgeKind>()
        var server = Badges()
        val store = BadgeStore({ server }, { reads += it }, pushes, CoroutineScope(Dispatchers.Unconfined))

        pushes.tryEmit(PushPayload(PushType.COMIC_DONE, comicId = 1))
        assertTrue(store.badges.value.comics)
        assertTrue(store.badges.value.inbox)

        // 서버는 받은 친구 요청이 남아 있다고 알려 줌 → 열어도 다시 켜짐
        server = Badges(friends = true)
        store.seen(BadgeKind.COMICS)
        assertEquals(listOf(BadgeKind.COMICS), reads)
        assertFalse(store.badges.value.comics)
        assertTrue(store.badges.value.friends)

        store.clear()
        assertEquals(Badges(), store.badges.value)
    }

    @Test
    fun `서버를 못 받으면 지금 점을 그대로 둔다`() {
        val pushes = MutableSharedFlow<PushPayload>(extraBufferCapacity = 4)
        val store = BadgeStore({ throw LologException(ErrorCodes.NETWORK) }, {}, pushes, CoroutineScope(Dispatchers.Unconfined))
        pushes.tryEmit(PushPayload(PushType.CARDS_CREATED))
        store.refresh()
        assertTrue(store.badges.value.cards)
    }

    @Test
    fun `마지막 로그인 방법·막힌 계정은 안내 문구가 있다`() {
        assertEquals("다른 로그인 방법을 먼저 연결해 주세요", errorText(LologException(ErrorCodes.LAST_LOGIN_METHOD)))
        assertEquals("이 계정으로는 가입하거나 연결할 수 없어요", errorText(LologException(ErrorCodes.ACCOUNT_BANNED)))
    }
}
