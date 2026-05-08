package com.terraworld.api.upload

import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import org.springframework.stereotype.Component
import java.net.URI
import java.net.URISyntaxException

/**
 * photoUrl 화이트리스트 검증 (SF-005 follow-up).
 *
 * RecordController.createRecord 가 받는 외부 URL 을 scheme/host/data MIME 단위로 차단한다.
 *  - generated DTO 의 URI 변환은 형식만 보장 → 도메인 화이트리스트는 본 컴포넌트 책임
 *  - 위반 시 [BusinessException] (ErrorCode.INVALID_PHOTO_URL, HTTP 400) throw
 *  - null / blank 는 통과 (사진 없음)
 *
 * 사용 예:
 * ```kotlin
 * photoUrlValidator.requireAllowed(createRecordRequest.photoUrl?.toString())
 * ```
 */
@Component
class PhotoUrlValidator(
    private val props: PhotoUrlProperties,
) {
    fun requireAllowed(photoUrl: String?) {
        if (photoUrl.isNullOrBlank()) return

        val uri =
            try {
                URI(photoUrl)
            } catch (_: URISyntaxException) {
                throw BusinessException(ErrorCode.INVALID_PHOTO_URL, "photoUrl 형식이 올바르지 않습니다")
            }

        val scheme =
            uri.scheme?.lowercase()
                ?: throw BusinessException(ErrorCode.INVALID_PHOTO_URL, "photoUrl scheme 누락")

        if (scheme !in props.allowedSchemes.map { it.lowercase() }) {
            throw BusinessException(ErrorCode.INVALID_PHOTO_URL, "허용되지 않은 scheme: $scheme")
        }

        when (scheme) {
            "https" -> validateHttps(uri)
            "data" -> validateDataUrl(uri.schemeSpecificPart)
            // allowedSchemes 가 확장되더라도 명시 안 된 경우 fail-closed
            else -> throw BusinessException(ErrorCode.INVALID_PHOTO_URL, "지원되지 않은 scheme: $scheme")
        }
    }

    private fun validateHttps(uri: URI) {
        // userInfo 차단 — `https://user:pass@evil.com/...` 변종 차단
        if (!uri.userInfo.isNullOrBlank()) {
            throw BusinessException(ErrorCode.INVALID_PHOTO_URL, "photoUrl 에 userInfo 는 허용되지 않습니다")
        }
        val host =
            uri.host?.lowercase()
                ?: throw BusinessException(ErrorCode.INVALID_PHOTO_URL, "photoUrl host 누락")
        if (!isAllowedHost(host)) {
            throw BusinessException(ErrorCode.INVALID_PHOTO_URL, "허용되지 않은 host: $host")
        }
    }

    private fun validateDataUrl(ssp: String?) {
        // data URL 형식: `<mime>[;<param>]*[;base64],<data>`
        // schemeSpecificPart 가 위 전체에 해당. mime 만 추출해 화이트리스트 비교.
        val raw = ssp ?: throw BusinessException(ErrorCode.INVALID_PHOTO_URL, "data URL 본문 누락")
        val commaIdx = raw.indexOf(',')
        if (commaIdx <= 0) {
            throw BusinessException(ErrorCode.INVALID_PHOTO_URL, "data URL 형식이 올바르지 않습니다")
        }
        val meta = raw.substring(0, commaIdx)
        val mime = meta.substringBefore(';').lowercase().ifBlank { "text/plain" }
        if (mime !in props.allowedDataMimes.map { it.lowercase() }) {
            throw BusinessException(ErrorCode.INVALID_PHOTO_URL, "허용되지 않은 data MIME: $mime")
        }
    }

    private fun isAllowedHost(host: String): Boolean =
        props.allowedHosts.any { entry ->
            val allow = entry.lowercase()
            when {
                allow.startsWith("*.") -> {
                    val suffix = allow.substring(1) // ".example.com"
                    host.endsWith(suffix) && host.length > suffix.length
                }
                else -> host == allow
            }
        }
}
