package com.terraworld.common.exception

import org.springframework.http.HttpStatus

enum class ErrorCode(val status: HttpStatus, val message: String) {
    // Auth
    DUPLICATE_EMAIL(HttpStatus.CONFLICT, "이미 사용 중인 이메일입니다"),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다"),
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "유효하지 않은 토큰입니다"),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "만료된 토큰입니다"),

    // Resource
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다"),
    ITEM_NOT_FOUND(HttpStatus.NOT_FOUND, "아이템을 찾을 수 없습니다"),
    CATEGORY_NOT_FOUND(HttpStatus.NOT_FOUND, "카테고리를 찾을 수 없습니다"),
    RECORD_NOT_FOUND(HttpStatus.NOT_FOUND, "기록을 찾을 수 없습니다"),
    NOTE_NOT_FOUND(HttpStatus.NOT_FOUND, "메모를 찾을 수 없습니다"),
    TERRARIUM_NOT_FOUND(HttpStatus.NOT_FOUND, "테라리움을 찾을 수 없습니다"),

    // Business
    INSUFFICIENT_FUNDS(HttpStatus.BAD_REQUEST, "재화가 부족합니다"),
    ALREADY_OWNED(HttpStatus.CONFLICT, "이미 보유한 아이템입니다"),
    INVALID_SLOT(HttpStatus.BAD_REQUEST, "해당 슬롯에 배치할 수 없는 아이템입니다"),
    SAME_CATEGORY_EXCHANGE(HttpStatus.BAD_REQUEST, "같은 카테고리로는 교환할 수 없습니다"),
    DAILY_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "일일 제한 횟수를 초과했습니다"),
    EXCHANGE_RATE_NOT_FOUND(HttpStatus.BAD_REQUEST, "교환 비율 정보를 찾을 수 없습니다"),

    // Phase 2 — 광고 보상
    AD_DAILY_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "오늘 광고 시청 한도를 모두 사용했습니다"),

    // Phase 2 — 초대
    INVITE_NOT_FOUND(HttpStatus.NOT_FOUND, "초대 코드를 찾을 수 없습니다"),
    INVITE_EXPIRED(HttpStatus.GONE, "만료된 초대 코드입니다"),
    INVITE_ALREADY_ACCEPTED(HttpStatus.CONFLICT, "이미 사용된 초대 코드입니다"),
    INVITE_SELF_ACCEPT(HttpStatus.BAD_REQUEST, "본인 초대 코드는 수락할 수 없습니다"),

    // General
    BAD_REQUEST(HttpStatus.BAD_REQUEST, "잘못된 요청입니다"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "권한이 없습니다"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다"),
}
