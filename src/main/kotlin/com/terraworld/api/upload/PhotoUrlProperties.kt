package com.terraworld.api.upload

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * photoUrl 화이트리스트 설정 (SF-005 follow-up — Phase 4 보안 hardening).
 *
 * RecordController.createRecord 가 받는 photoUrl 은 generated DTO 단계에서 URI 형식만 검증된다.
 * `https://attacker.com/evil.jpg` / `javascript:...` / `file:///etc/passwd` 같은 임의 URL 도
 * 형식상 유효하므로 통과된다. 본 properties + PhotoUrlValidator 가 scheme/host/data MIME 화이트리스트로 차단.
 *
 * 환경별 override 예 (application-prod.yml):
 *   app.photo-url.allowed-hosts: cdn.terraworld.app
 *
 * 와일드카드 host 는 `*.terraworld.app` 형식 — 정확히 한 단계 prefix 만 허용 (`a.b.terraworld.app` 도 허용).
 */
@ConfigurationProperties(prefix = "app.photo-url")
data class PhotoUrlProperties(
    /**
     * 허용 scheme 목록 (lowercase 비교).
     *  - `https` : 외부 CDN URL
     *  - `data`  : PhotoUploadService 가 생성하는 base64 dataURL (allowedDataMimes 와 함께 검증)
     */
    val allowedSchemes: List<String> = listOf("https", "data"),
    /**
     * 허용 host 목록 (lowercase 비교).
     *  - 정확 일치 또는 `*.example.com` 와일드카드 형식.
     *  - 빈 리스트 면 https 검증이 항상 실패 → 사실상 data URL 만 허용.
     */
    val allowedHosts: List<String> = listOf("cdn.terraworld.app", "terraworld.app"),
    /**
     * data URL 의 MIME 화이트리스트 (lowercase 비교).
     * PhotoUploadService.ALLOWED_MIMES 와 일치해야 함 (서버가 만든 dataURL 만 통과).
     */
    val allowedDataMimes: List<String> = listOf("image/jpeg", "image/png", "image/webp"),
)
