package com.lolog.app.data.model

/**
 * 앱 안 새 소식 점(LOLog-docs spec/inbox.md 4장, GET /api/me/badges).
 * 기능마다 새로 온 게 있는지만 알려 줘요(숫자 아님). 친구 탭의 빨간 숫자는 따로 받은 요청 수로 보여 줘요.
 */
data class Badges(
    /** 보관함: 새 카드 */
    val cards: Boolean = false,
    /** 컨텐츠: 만화·쇼츠 완성·실패 */
    val comics: Boolean = false,
    /** 내 캐릭터: 다 만듦·실패 */
    val profileCharacter: Boolean = false,
    /** 친구: 받은 친구 요청이 있음 */
    val friends: Boolean = false,
    /** 친구 방: 초대, 내 만화 댓글, 해산, 받지 않은 글 */
    val rooms: Boolean = false,
    /** 종 버튼: 안 읽은 알림·공지사항(GET /api/inbox/unread와 같음) */
    val inbox: Boolean = false,
) {
    fun with(kind: BadgeKind, on: Boolean) = when (kind) {
        BadgeKind.CARDS -> copy(cards = on)
        BadgeKind.COMICS -> copy(comics = on)
        BadgeKind.PROFILE_CHARACTER -> copy(profileCharacter = on)
        BadgeKind.FRIENDS -> copy(friends = on)
        BadgeKind.ROOMS -> copy(rooms = on)
        BadgeKind.INBOX -> copy(inbox = on)
    }

    /** 친구 탭 아이콘에 점을 띄울지(받은 요청이 있으면 점 대신 빨간 숫자) */
    val friendsTab get() = friends || rooms || profileCharacter
}

/** POST /api/me/badges/{kind}/read의 kind. path가 서버에 보내는 글자예요 */
enum class BadgeKind(val path: String) {
    CARDS("cards"), COMICS("comics"), PROFILE_CHARACTER("profileCharacter"), FRIENDS("friends"), ROOMS("rooms"), INBOX("inbox");

    companion object {
        /** 푸시 종류마다 켤 점(inbox.md 4장 표, 7장: 쇼츠는 comics 점). 모든 푸시는 알림 목록에도 남아 종 버튼 점도 켜요 */
        fun of(type: PushType): BadgeKind = when (type) {
            PushType.CARDS_CREATED -> CARDS
            PushType.COMIC_DONE, PushType.COMIC_FAILED, PushType.VIDEO_DONE, PushType.VIDEO_FAILED -> COMICS
            PushType.PROFILE_CHARACTER_READY, PushType.PROFILE_CHARACTER_FAILED -> PROFILE_CHARACTER
            PushType.FRIEND_REQUEST -> FRIENDS
            PushType.ROOM_INVITE, PushType.ROOM_COMMENT, PushType.ROOM_DISBANDED -> ROOMS
        }
    }
}
