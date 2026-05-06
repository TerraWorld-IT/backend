package com.terraworld.api.userdevice

import com.terraworld.common.audit.AuditService
import com.terraworld.domain.userdevice.DevicePlatform
import com.terraworld.domain.userdevice.UserDevice
import com.terraworld.domain.userdevice.UserDeviceRepository
import com.terraworld.test.FakeJpaRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * UserDeviceService 의 멱등 upsert 검증.
 *
 * - 신규 (user, token) → INSERT + audit DEVICE_REGISTER 발행
 * - 기존 (user, token) → UPDATE (lastSeenAt / platform / appVersion / isActive=true) + audit 발행 안 함
 * - 비활성화된 row → 재호출 시 다시 활성화
 * - 잘못된 platform 문자열 → IllegalArgumentException
 */
class UserDeviceServiceTest {
    private lateinit var repo: FakeRepo
    private lateinit var auditService: AuditService
    private lateinit var service: UserDeviceService

    @BeforeEach
    fun setup() {
        repo = FakeRepo()
        auditService = mock()
        service = UserDeviceService(repo, auditService)
    }

    @Test
    fun `신규 토큰은 INSERT 되며 audit 발행`() {
        val response =
            service.upsert(
                "user-1",
                DeviceRegistrationRequest(token = "fcm-token-A", platform = "ANDROID", appVersion = "1.0.0"),
            )

        assertEquals(1, repo.all().size)
        val saved = repo.all().first()
        assertEquals("user-1", saved.userId)
        assertEquals("fcm-token-A", saved.token)
        assertEquals(DevicePlatform.ANDROID, saved.platform)
        assertEquals("1.0.0", saved.appVersion)
        assertTrue(saved.isActive)
        assertEquals(saved.id, response.deviceId)
        assertNotNull(response.registeredAt)

        // audit publish 1회 (DEVICE_REGISTER)
        verify(auditService).publish(
            userId = eq("user-1"),
            action = eq("DEVICE_REGISTER"),
            resourceType = eq("UserDevice"),
            resourceId = any(),
            payload = any(),
        )
    }

    @Test
    fun `동일 (user, token) 재호출은 UPDATE — row 1개 유지`() {
        service.upsert("user-1", DeviceRegistrationRequest(token = "fcm-A", platform = "ANDROID"))
        val firstRow = repo.all().first()
        val firstSeenAt = firstRow.lastSeenAt

        Thread.sleep(5)

        val response =
            service.upsert(
                "user-1",
                DeviceRegistrationRequest(token = "fcm-A", platform = "ANDROID", appVersion = "1.1.0"),
            )

        assertEquals(1, repo.all().size, "row 가 추가되면 안 됨")
        assertEquals(firstRow.id, response.deviceId)

        val updated = repo.all().first()
        assertTrue(updated.lastSeenAt.isAfter(firstSeenAt) || updated.lastSeenAt == firstSeenAt)
        assertEquals("1.1.0", updated.appVersion)
    }

    @Test
    fun `비활성화된 row 는 재호출 시 활성화`() {
        // 사전 조건: 기존 row 가 isActive=false 로 들어가있음
        val pre =
            UserDevice(
                userId = "user-1",
                token = "fcm-A",
                platform = DevicePlatform.ANDROID,
                isActive = false,
            )
        repo.save(pre)

        service.upsert("user-1", DeviceRegistrationRequest(token = "fcm-A", platform = "ANDROID"))

        assertEquals(1, repo.all().size)
        assertTrue(repo.all().first().isActive)
        // 기존 row 의 update 라 audit publish 호출 안 됨
        verify(auditService, never()).publish(any(), any(), any(), any(), any())
    }

    @Test
    fun `다른 사용자가 같은 토큰을 등록하면 별도 row`() {
        service.upsert("user-1", DeviceRegistrationRequest(token = "shared-token", platform = "ANDROID"))
        service.upsert("user-2", DeviceRegistrationRequest(token = "shared-token", platform = "ANDROID"))

        assertEquals(2, repo.all().size)
    }

    @Test
    fun `잘못된 platform 문자열은 IllegalArgumentException`() {
        assertThrows<IllegalArgumentException> {
            service.upsert(
                "user-1",
                DeviceRegistrationRequest(token = "fcm-A", platform = "WINDOWS_PHONE"),
            )
        }
        assertEquals(0, repo.all().size)
    }

    private class FakeRepo :
        FakeJpaRepository<UserDevice, Long>(),
        UserDeviceRepository {
        private var nextId = 1L

        override fun extractId(entity: UserDevice): Long = entity.id

        override fun assignId(entity: UserDevice): UserDevice {
            if (entity.id != 0L) return entity
            val idField = UserDevice::class.java.getDeclaredField("id")
            idField.isAccessible = true
            idField.setLong(entity, nextId++)
            return entity
        }

        override fun findByUserIdAndToken(
            userId: String,
            token: String,
        ): Optional<UserDevice> =
            Optional.ofNullable(
                store.values.firstOrNull { it.userId == userId && it.token == token },
            )

        override fun findAllByUserIdAndIsActiveTrue(userId: String): List<UserDevice> = store.values.filter { it.userId == userId && it.isActive }
    }
}
