package com.terraworld.domain.userdevice

import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional

interface UserDeviceRepository : JpaRepository<UserDevice, Long> {
    fun findByUserIdAndToken(
        userId: String,
        token: String,
    ): Optional<UserDevice>

    fun findAllByUserIdAndIsActiveTrue(userId: String): List<UserDevice>
}
