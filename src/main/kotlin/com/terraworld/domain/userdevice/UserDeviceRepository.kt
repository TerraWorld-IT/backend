package com.terraworld.domain.userdevice

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.util.Optional

interface UserDeviceRepository : JpaRepository<UserDevice, Long> {
    /** 로그아웃 시 해당 사용자의 활성 디바이스를 단일 쿼리로 비활성화한다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE UserDevice d SET d.isActive = false WHERE d.userId = :userId AND d.isActive = true")
    fun deactivateAllByUserId(userId: String): Int

    /** 계정 삭제 시 FK 없는 사용자 행을 즉시 일괄 삭제한다. */
    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM UserDevice e WHERE e.userId = :userId")
    fun deleteAllByUserId(userId: String): Int

    fun findByUserIdAndToken(
        userId: String,
        token: String,
    ): Optional<UserDevice>

    fun findAllByUserIdAndIsActiveTrue(userId: String): List<UserDevice>

    /**
     * SEC-003: FCM/APNs 토큰은 device-scoped 이므로 한 토큰을 두 user 가 동시에
     * 보유할 수 없다. 신규 등록 전 이 메서드로 다른 user 의 row 가 있는지 확인하고,
     * 있으면 비활성화 처리한다.
     */
    fun findAllByTokenAndIsActiveTrue(token: String): List<UserDevice>
}
