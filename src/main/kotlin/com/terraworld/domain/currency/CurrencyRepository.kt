package com.terraworld.domain.currency

import org.springframework.data.jpa.repository.JpaRepository

interface CurrencyRepository : JpaRepository<Currency, String> {
    fun findAllByOrderBySortOrderAsc(): List<Currency>
}
