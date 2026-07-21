package com.terraworld.domain.user

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface UserRepository : JpaRepository<User, String> {
    /**
     * 유저 단위 bootstrap 직렬화 — 동시 첫 요청의 provisioning 경쟁을 tx-scoped advisory
     * lock 으로 순차화한다 (Codex R1 #3: save() 의 INSERT 는 flush 까지 지연될 수 있어
     * DataIntegrityViolationException catch 가 경쟁을 못 잡고, 공유 tx 는 rollback-only 가 됨).
     * 패자는 lock 대기 후 재조회에서 존재를 보고 정상 return 한다.
     * (HabitTrackerRepository.acquireHabitPairLock 과 동일 패턴.)
     */
    @Query(
        value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))) AS _lock",
        nativeQuery = true,
    )
    fun acquireBootstrapLock(
        @Param("key") key: String,
    ): Int
}
