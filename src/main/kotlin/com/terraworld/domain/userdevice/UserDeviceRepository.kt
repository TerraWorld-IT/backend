package com.terraworld.domain.userdevice

import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional

interface UserDeviceRepository : JpaRepository<UserDevice, Long> {
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
