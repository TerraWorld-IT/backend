package com.terraworld.common.cache

/**
 * BE-08 (2026-07-15 성능 감사): 정적 config 로컬 캐시 이름 SoT.
 *
 * 대상은 seed/admin 이 아니면 바뀌지 않는 정적 config 조회 — admin 변경 시 stale 은
 * TTL 10분([com.terraworld.config.CacheConfig])으로 수용한다 (성능 감사 결정).
 *
 * 캐시는 Spring Data repository 인터페이스 메서드에 `@Cacheable` 로 부착한다
 * (repository 는 JDK proxy 라 인터페이스 어노테이션이 적용됨). 서비스 내부
 * private 메서드에 붙이면 self-invocation 으로 캐시가 안 걸리는 함정을 회피.
 *
 * ⚠️ 엔티티를 캐시하는 항목(growth species/stages, system categories)은 detached 상태로
 * 공유되므로 **read-only 사용 전용** — 수정/save 금지. lazy 연관이 있는 엔티티(Item 등)는
 * 엔티티 캐시 금지 → DTO 로 매핑 후 캐시([com.terraworld.api.item.ItemCatalogService]).
 */
object CacheNames {
    /** 화폐 코드 존재 여부 (CurrencyService.validateCurrency) — Boolean. */
    const val CURRENCY_EXISTS = "currency-exists"

    /** 티어별 슬롯 수 (TerrariumService maxSlots) — Int 스칼라. */
    const val TIER_SLOTS = "tier-slots"

    /** 육성 종 목록 (GrowthService) — 엔티티(관계 없음), read-only. */
    const val GROWTH_SPECIES = "growth-species"

    /** kind → 육성 stage 목록 (GrowthService) — 엔티티(관계 없음), read-only. */
    const val GROWTH_STAGES = "growth-stages"

    /** 활성 시스템 카테고리 목록 (CategoryController / RecordService.getStatistics) — read-only. */
    const val SYSTEM_CATEGORIES = "system-categories"

    /** 활성 아이템 카탈로그 (ItemCatalogService) — generated DTO 로 매핑 후 캐시. */
    const val ITEM_CATALOG = "item-catalog"

    val ALL =
        arrayOf(
            CURRENCY_EXISTS,
            TIER_SLOTS,
            GROWTH_SPECIES,
            GROWTH_STAGES,
            SYSTEM_CATEGORIES,
            ITEM_CATALOG,
        )
}
