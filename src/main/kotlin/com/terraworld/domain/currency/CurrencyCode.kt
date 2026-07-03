package com.terraworld.domain.currency

/**
 * 화폐 코드 단일 SoT (7종 고정). 낙서장 리팩토링.
 * `currencies` 테이블 seed(V25) · OpenAPI enum · WalletBuilder · CurrencyService 가 모두 본 상수를 참조해
 * 문자열 리터럴 중복/오타 drift 를 차단한다(quality-review #1).
 */
object CurrencyCode {
    const val COIN = "COIN" // 코인 (범용)
    const val RUBY = "RUBY" // 루비 (유료 소비성)
    const val SPARKLE = "SPARKLE" // 반짝이 (습관 보상 / 부스터)
    const val DEW = "DEW" // 이슬 토큰 (산책)
    const val SUN = "SUN" // 햇살 토큰 (독서)
    const val BOLT = "BOLT" // 번개 토큰 (러닝)
    const val WIND = "WIND" // 바람 토큰 (낙서)

    /** 7종 전체 (표시 순서). */
    val ALL: List<String> = listOf(COIN, RUBY, SPARKLE, DEW, SUN, BOLT, WIND)

    /**
     * 시스템 카테고리(1~4) → 원소 토큰 매핑 단일 SoT. 낙서장 확장(+@ 카테고리) 시 본 맵만 갱신.
     * 커스텀 카테고리(5+)는 전용 원소 토큰이 없음 → [elementTokenForCategory] 가 null 반환.
     * PurchaseService(debit) 와 RecordService(credit) 가 동일 맵을 참조해 category→token drift 를 차단한다.
     */
    val CATEGORY_ELEMENT_TOKEN: Map<Long, String> =
        mapOf(
            1L to DEW, // 산책
            2L to SUN, // 독서
            3L to BOLT, // 러닝
            4L to WIND, // 낙서
        )

    /** 카테고리 → 원소 토큰 코드. 시스템 카테고리(1~4)만 매핑, 커스텀(5+)은 null(전용 토큰 없음). */
    fun elementTokenForCategory(categoryId: Long): String? = CATEGORY_ELEMENT_TOKEN[categoryId]
}
