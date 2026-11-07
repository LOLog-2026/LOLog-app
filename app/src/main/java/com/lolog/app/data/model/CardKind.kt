package com.lolog.app.data.model

/** 카드 종류(협의 1-1 기본안). 서버 값("웃김" 등) ↔ enum 변환은 ApiMapper에서 해요. */
enum class CardKind(val label: String) {
    FUNNY("웃김"), ABSURD("황당"), MEMORY("추억"), OTHER("기타"),
}

/** 이름을 안 고친 화자는 speakers 순서대로 "화자1", "화자2"(협의 1-2 기본안) */
fun List<Speaker>.displayName(key: String): String {
    val index = indexOfFirst { it.key == key }
    if (index < 0) return key
    return this[index].name?.takeIf { it.isNotBlank() } ?: "화자${index + 1}"
}

fun List<Speaker>.displayNames(): List<String> = map { displayName(it.key) }
