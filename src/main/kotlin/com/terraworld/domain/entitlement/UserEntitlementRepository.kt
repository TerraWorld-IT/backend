package com.terraworld.domain.entitlement

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface UserEntitlementRepository : JpaRepository<UserEntitlement, UserEntitlementId> {
    fun findAllByIdUserId(userId: String): List<UserEntitlement>

    fun existsByIdUserIdAndIdEntitlementKey(
        userId: String,
        entitlementKey: String,
    ): Boolean

    fun deleteByIdUserIdAndIdEntitlementKey(
        userId: String,
        entitlementKey: String,
    ): Long
}

@Repository
interface EntitlementEventRepository : JpaRepository<EntitlementEvent, Long> {
    fun findAllByUserIdAndEntitlementKeyOrderByOccurredAtDesc(
        userId: String,
        entitlementKey: String,
    ): List<EntitlementEvent>

    fun findAllByUserIdOrderByOccurredAtDesc(userId: String): List<EntitlementEvent>
}
