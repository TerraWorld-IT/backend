package com.terraworld.api.notification

import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.domain.notification.UserNotification
import com.terraworld.security.SecurityUtil
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import io.terraworld.api.api.NotificationApi
import io.terraworld.api.model.MessageResponse
import io.terraworld.api.model.NotificationReadRequest
import io.terraworld.api.model.NotificationResponse
import io.terraworld.api.model.NotificationUnreadCountResponse
import io.terraworld.api.model.PagedNotificationResponse
import jakarta.validation.Valid
import org.springframework.data.domain.PageRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.ZoneOffset
import java.util.UUID

/**
 * NotificationApi 구현 — 인앱 알림함 (apjek social loop, V38).
 *
 * 조회/읽음 처리만 담당 — 알림 적재는 [NotificationEventListener] 가 도메인 이벤트
 * 커밋 확정 후 수행한다 (controller 는 적재 경로 없음). 인증·예외·응답 조립은
 * 기존 컨트롤러 관례 (SecurityUtil + service 위임 + generated DTO 직접 반환).
 */
@Tag(name = "Notification", description = "인앱 알림함 API")
@RestController
@RequestMapping("/api/v1")
class NotificationController(
    private val notificationService: NotificationService,
) : NotificationApi {
    @Operation(summary = "미읽음 알림 카운트", description = "뱃지 표시용 — readAt IS NULL 건수")
    override fun getUnreadNotificationCount(): ResponseEntity<NotificationUnreadCountResponse> =
        ResponseEntity.ok(
            NotificationUnreadCountResponse(count = notificationService.unreadCount(SecurityUtil.getCurrentUserId())),
        )

    @Operation(summary = "인앱 알림 목록 조회", description = "최신순 정렬, 기본 20개/최대 50개 페이지네이션")
    override fun listNotifications(
        page: Int,
        size: Int,
    ): ResponseEntity<PagedNotificationResponse> {
        val result = notificationService.list(SecurityUtil.getCurrentUserId(), PageRequest.of(page, size))
        return ResponseEntity.ok(
            PagedNotificationResponse(
                content = result.content.map { toApi(it) },
                page = result.number,
                propertySize = result.size,
                totalElements = result.totalElements,
                totalPages = result.totalPages,
            ),
        )
    }

    @Operation(summary = "알림 읽음 처리", description = "ids 가 빈 배열이면 전체 읽음 처리. 이미 읽은 항목 포함도 멱등.")
    override fun markNotificationsRead(
        @Valid notificationReadRequest: NotificationReadRequest,
    ): ResponseEntity<MessageResponse> {
        // spec 의 ids 는 string — UUID 형식이 아니면 400 (모르는 정상 UUID 는 service 가 조용히 무시).
        val ids =
            notificationReadRequest.ids.map { raw ->
                runCatching { UUID.fromString(raw) }
                    .getOrElse { throw BusinessException(ErrorCode.INVALID_INPUT, "잘못된 알림 ID 형식입니다") }
            }
        notificationService.markRead(SecurityUtil.getCurrentUserId(), ids)
        return ResponseEntity.ok(MessageResponse(message = "알림을 읽음 처리했습니다"))
    }

    private fun toApi(n: UserNotification): NotificationResponse =
        NotificationResponse(
            // 영속 엔티티는 id 가 항상 채워진다 — null 이면 저장 경로 계약 위반 (SF-003 fail-fast 관례).
            id = checkNotNull(n.id) { "저장된 UserNotification 의 id 가 null — 저장 경로 계약 위반" }.toString(),
            // domain NotificationType 은 spec enum 과 1:1 (V38 CHECK 동일 집합) — forValue 직접 변환.
            type = NotificationResponse.Type.forValue(n.type.name),
            title = n.title,
            body = n.body,
            createdAt = n.createdAt.atOffset(ZoneOffset.UTC),
            route = n.route,
            readAt = n.readAt?.atOffset(ZoneOffset.UTC),
        )
}
