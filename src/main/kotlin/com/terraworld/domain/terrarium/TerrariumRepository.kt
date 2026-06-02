package com.terraworld.domain.terrarium

import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional

interface TerrariumRepository : JpaRepository<Terrarium, Long> {
    fun findByUserId(userId: String): Optional<Terrarium>
}

interface TerrariumBackgroundRepository : JpaRepository<TerrariumBackground, Long>

interface TerrariumPlacementRepository : JpaRepository<TerrariumPlacement, Long> {
    fun findAllByTerrariumId(terrariumId: Long): List<TerrariumPlacement>

    // 자유배치 로드용 (free.vue) — terrarium.user.id 로 현재 사용자의 모든 배치 조회.
    // @EntityGraph(item): toFreeItem() 이 item 을 읽으므로 N+1 회피. OrderById: 응답 순서 결정성
    // (frontend 가 index 로 z-index 부여하므로 안정 정렬 필요).
    @EntityGraph(attributePaths = ["item"])
    fun findAllByTerrariumUserIdOrderById(userId: String): List<TerrariumPlacement>

    fun deleteAllByTerrariumId(terrariumId: Long)
}
