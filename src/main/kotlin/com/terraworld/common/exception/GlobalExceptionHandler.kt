package com.terraworld.common.exception

import com.terraworld.api.entitlement.EntitlementTxRefConflictException
import com.terraworld.common.dto.ErrorResponse
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(BusinessException::class)
    fun handleBusinessException(e: BusinessException): ResponseEntity<ErrorResponse> =
        ResponseEntity
            .status(e.errorCode.status)
            .body(ErrorResponse(code = e.errorCode.name, message = e.message))

    /**
     * /analyze 2026-05-18 (Codex F3): V15 partial UNIQUE (terrarium_items.slot_id)
     * 위반 시 Hibernate 가 DataIntegrityViolationException 으로 던지는데, 매핑이 없어
     * Spring 기본 500 + stacktrace 누출. 본 핸들러가 4xx + 일반 메시지로 변환.
     *
     * 패턴 매칭 (Codex code-review CDX-001 HIGH 정정):
     *   - **정확 constraint name 매칭만** (`ux_terrarium_items_terrarium_slot`) → TERRARIUM_SLOT_CONFLICT (409)
     *   - 그 외 (terrarium_items 의 FK / NOT NULL / 다른 unique) → BAD_REQUEST (400)
     *   - 이전 광범위 `contains("terrarium_items")` 는 모든 integrity 위반을 409 로 오분류 → 제거.
     *
     * 서버 로그 (SEC-401 정정): rootMessage 직접 노출 대신 exception class + SQLState 만 — column 값
     * (e.g. PostgreSQL `Detail: Key (...)=(abc, 3)`) 의 internal log leak 차단.
     */
    @ExceptionHandler(DataIntegrityViolationException::class)
    fun handleDataIntegrity(e: DataIntegrityViolationException): ResponseEntity<ErrorResponse> {
        val rootMessage = e.mostSpecificCause.message.orEmpty()
        val sqlState = (e.rootCause as? java.sql.SQLException)?.sqlState
        log.warn("DataIntegrityViolation — class={} sqlState={}", e.javaClass.simpleName, sqlState)

        return when {
            // V15 키(ux_terrarium_items_terrarium_slot) 는 V39 에서 티어 스코프 키(ux_terrarium_items_terrarium_tier_slot)로
            // 대체됐다 — 두 이름 모두 같은 의미(같은 병·같은 슬롯 중복)라 409 로 매핑.
            rootMessage.contains("ux_terrarium_items_terrarium_slot") ||
                rootMessage.contains("ux_terrarium_items_terrarium_tier_slot") -> {
                val code = ErrorCode.TERRARIUM_SLOT_CONFLICT
                ResponseEntity
                    .status(code.status)
                    .body(ErrorResponse(code = code.name, message = code.message))
            }
            else ->
                ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body(ErrorResponse(code = "BAD_REQUEST", message = "데이터 제약 조건 위반"))
        }
    }

    /**
     * N10 (구현 계획서 v4): JPA optimistic lock 충돌 — 동시 재화/EXP 변동 시 user.version
     * mismatch 로 Hibernate 가 ObjectOptimisticLockingFailureException 을 던짐. 매핑 부재 시
     * Spring 기본 500. 본 핸들러가 409 + 재시도 유도 메시지로 변환.
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException::class)
    fun handleOptimisticLock(e: ObjectOptimisticLockingFailureException): ResponseEntity<ErrorResponse> {
        log.warn("ObjectOptimisticLockingFailure — entity={}", e.persistentClassName)
        val code = ErrorCode.CONCURRENT_MODIFICATION
        return ResponseEntity
            .status(code.status)
            .body(ErrorResponse(code = code.name, message = code.message))
    }

    /**
     * code-review CDX-002 (2026-05-21): `require(...)` (precondition 검증) 가 던지는
     * IllegalArgumentException 이 미매핑이라 500 으로 누출. precondition 위반은 client
     * 오류이므로 400 으로 매핑. (IllegalStateException 은 별도 — boot fail 등 서버 오류.)
     */
    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(e: IllegalArgumentException): ResponseEntity<ErrorResponse> {
        log.warn("IllegalArgument — {}", e.message)
        // review LOW: enum `valueOf` 실패 시 JVM 이 던지는 "No enum constant <FQCN>" 메시지는
        // 내부 클래스명을 미인증 호출자에게 누출한다(예: GET /items?rarity=XYZ). 이 패턴만
        // 일반 메시지로 마스킹하고, require(...) 등 의도된 user-facing 메시지는 그대로 전달.
        val safeMessage =
            if (e.message?.startsWith("No enum constant") == true) {
                "잘못된 요청입니다"
            } else {
                e.message ?: "잘못된 요청입니다"
            }
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(ErrorResponse(code = "BAD_REQUEST", message = safeMessage))
    }

    /**
     * V36 (2026-07-15): entitlement tx_ref 충돌 — 실제 실패이므로 500 으로 명시 매핑.
     *
     * 미매핑 상태에서는 예외가 컨테이너 /error 로 흘러 Security 게이트에서 **401 로 관측**됐다
     * (bootRun 스모크 실측). 401 은 운영에서 인증 실패로 오독되고 IAP 클라이언트의 재인증
     * 루프를 유발할 수 있다. Pub/Sub/클라이언트는 non-2xx 를 모두 재전송 대상으로 취급하므로
     * 재전송 계약은 동일 — 상태코드 의미만 정정. 메시지는 일반 문구 (token/txRef 비노출).
     */
    @ExceptionHandler(EntitlementTxRefConflictException::class)
    fun handleEntitlementTxRefConflict(e: EntitlementTxRefConflictException): ResponseEntity<ErrorResponse> {
        log.error("EntitlementTxRefConflict — userId={} key={}", e.userId, e.entitlementKey)
        val code = ErrorCode.INTERNAL_ERROR
        return ResponseEntity
            .status(code.status)
            .body(ErrorResponse(code = code.name, message = code.message))
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(e: MethodArgumentNotValidException): ResponseEntity<ErrorResponse> {
        val message =
            e.bindingResult.fieldErrors
                .joinToString(", ") { "${it.field}: ${it.defaultMessage}" }
        return ResponseEntity
            .badRequest()
            .body(ErrorResponse(code = "VALIDATION_ERROR", message = message))
    }

    /**
     * SEC-025: map AccessDeniedException to a 403 with a generic message
     * instead of letting Spring leak a 500 + stacktrace. Triggered today
     * by [com.terraworld.api.internal.InternalUserController] when the
     * shared internal token is missing or wrong; will also catch any
     * future @PreAuthorize denials in the same way.
     */
    @ExceptionHandler(AccessDeniedException::class)
    fun handleAccessDenied(e: AccessDeniedException): ResponseEntity<ErrorResponse> =
        ResponseEntity
            .status(HttpStatus.FORBIDDEN)
            .body(ErrorResponse(code = "FORBIDDEN", message = "access denied"))
}
