package com.terraworld.common.exception

import org.springframework.http.HttpStatus

enum class ErrorCode(
    val status: HttpStatus,
    val message: String,
) {
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
    PLACEMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "배치 정보를 찾을 수 없습니다"),
    HABIT_NOT_FOUND(HttpStatus.NOT_FOUND, "습관 트래커를 찾을 수 없습니다"),

    // Business
    INSUFFICIENT_FUNDS(HttpStatus.BAD_REQUEST, "재화가 부족합니다"),
    INVALID_CURRENCY(HttpStatus.BAD_REQUEST, "유효하지 않은 화폐 코드입니다"),
    AMOUNT_OVERFLOW(HttpStatus.BAD_REQUEST, "금액이 허용 범위를 초과했습니다"),
    PAIR_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "허용되지 않는 교환 조합입니다"),
    AMOUNT_TOO_SMALL(HttpStatus.BAD_REQUEST, "교환 결과가 1 미만입니다"),
    ALREADY_OWNED(HttpStatus.CONFLICT, "이미 보유한 아이템입니다"),
    ITEM_SLUG_DUPLICATE(HttpStatus.CONFLICT, "이미 존재하는 슬러그입니다"),
    INVALID_SLOT(HttpStatus.BAD_REQUEST, "해당 슬롯에 배치할 수 없는 아이템입니다"),

    // V15 (slot_id partial UNIQUE) 위반 시 DataIntegrityViolationException 매핑 (Codex F3).
    TERRARIUM_SLOT_CONFLICT(HttpStatus.CONFLICT, "해당 슬롯에 이미 다른 아이템이 배치되어 있습니다"),
    SAME_CATEGORY_EXCHANGE(HttpStatus.BAD_REQUEST, "같은 카테고리로는 교환할 수 없습니다"),
    DAILY_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "일일 제한 횟수를 초과했습니다"),
    EXCHANGE_RATE_NOT_FOUND(HttpStatus.BAD_REQUEST, "교환 비율 정보를 찾을 수 없습니다"),

    // Phase 2 — 광고 보상
    AD_DAILY_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "오늘 광고 시청 한도를 모두 사용했습니다"),

    // N9 (구현 계획서 v4, 2026-05-26): 같은 nonce 로 이미 광고 보상 수령됨 (replay 차단)
    NONCE_ALREADY_CONSUMED(HttpStatus.CONFLICT, "이미 처리된 광고 시청 요청입니다"),

    // Phase 2 — 초대
    INVITE_NOT_FOUND(HttpStatus.NOT_FOUND, "초대 코드를 찾을 수 없습니다"),
    INVITE_EXPIRED(HttpStatus.GONE, "만료된 초대 코드입니다"),
    INVITE_ALREADY_ACCEPTED(HttpStatus.CONFLICT, "이미 사용된 초대 코드입니다"),
    INVITE_SELF_ACCEPT(HttpStatus.BAD_REQUEST, "본인 초대 코드는 수락할 수 없습니다"),

    // Phase 3 — 진화 / 커스텀 카테고리 / 출석 / Joint record
    FORBIDDEN_EVOLUTION(HttpStatus.FORBIDDEN, "해당 진화 단계로 전환할 수 없습니다"),
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "잘못된 입력입니다"),
    CATEGORY_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "커스텀 카테고리 최대 개수(10개)를 초과했습니다"),
    CATEGORY_NAME_DUPLICATE(HttpStatus.CONFLICT, "이미 존재하는 카테고리 이름입니다"),
    CATEGORY_PROTECTED(HttpStatus.FORBIDDEN, "시스템 카테고리는 수정/삭제할 수 없습니다"),
    ATTENDANCE_ALREADY_CHECKED(HttpStatus.CONFLICT, "오늘 이미 출석 보상을 받았습니다"),
    INVALID_PARTNER(HttpStatus.BAD_REQUEST, "함께 기록할 수 없는 사용자입니다"),

    // Phase 4 — photoUrl 도메인 화이트리스트 (SF-005 follow-up)
    INVALID_PHOTO_URL(HttpStatus.BAD_REQUEST, "허용되지 않은 사진 URL 입니다"),

    // General
    BAD_REQUEST(HttpStatus.BAD_REQUEST, "잘못된 요청입니다"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "권한이 없습니다"),
    RATE_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요"),

    // N10 (구현 계획서 v4): optimistic lock 충돌 (동시 재화/EXP 변동) — 클라이언트 재시도 유도
    CONCURRENT_MODIFICATION(HttpStatus.CONFLICT, "다른 요청이 동시에 처리 중입니다. 잠시 후 다시 시도해 주세요"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다"),
}
