package com.terraworld.domain.currency

import com.terraworld.common.cache.CacheNames
import org.springframework.cache.annotation.Cacheable
import org.springframework.data.jpa.repository.JpaRepository

interface CurrencyRepository : JpaRepository<Currency, String> {
    fun findAllByOrderBySortOrderAsc(): List<Currency>

    /**
     * BE-08: 화폐 코드 존재 검증 (CurrencyService.validateCurrency hot path — 매 credit 마다 호출).
     * 화폐는 7종 고정 seed(V25) — Caffeine TTL 10분 캐시로 검증 SELECT 제거. Boolean scalar 라
     * detached entity 문제 없음. 미존재 코드의 false 도 캐시됨(고정 seed 라 무해).
     */
    @Cacheable(CacheNames.CURRENCY_EXISTS)
    fun existsByCode(code: String): Boolean
}
