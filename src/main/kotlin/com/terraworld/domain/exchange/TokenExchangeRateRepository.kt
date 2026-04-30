package com.terraworld.domain.exchange

import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional

interface TokenExchangeRateRepository : JpaRepository<TokenExchangeRate, Long> {
    fun findByFromCategoryIdAndToCategoryIdAndIsActiveTrue(
        fromId: Long,
        toId: Long,
    ): Optional<TokenExchangeRate>
}
