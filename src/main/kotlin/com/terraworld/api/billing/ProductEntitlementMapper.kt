package com.terraworld.api.billing

/**
 * Play/IAP 상품 ID → entitlement key 매핑 (단일 SoT). (fullstack-ultraplan WP-1, 2026-06-04)
 *
 * 프론트(usePayment.startPurchase) · Play Console 상품 등록 · 백엔드(IapVerifyController +
 * PlayBillingWebhookController) 가 모두 본 매핑 기준으로 동기되어야 한다. 신규 상품 추가 시
 * 본 object 한 곳만 수정 → 전 경로 일관.
 *
 * - free_placement_unlock (₩1,900, 1회 영구) → free_placement
 * - season_pass_*                            → 동일 key
 * - limited_figure_*                         → 동일 key
 * - memo_theme_pack_*                        → theme_pack_*
 */
object ProductEntitlementMapper {
    fun toEntitlementKey(productId: String): String? =
        when {
            productId == "free_placement_unlock" -> "free_placement"
            productId.startsWith("season_pass_") -> productId
            productId.startsWith("limited_figure_") -> productId
            productId.startsWith("memo_theme_pack_") -> productId.replace("memo_theme_pack_", "theme_pack_")
            else -> null
        }
}
