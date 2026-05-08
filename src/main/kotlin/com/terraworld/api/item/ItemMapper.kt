package com.terraworld.api.item

import com.terraworld.domain.item.Item
import io.terraworld.api.model.ItemResponse
import org.slf4j.LoggerFactory

/**
 * Item entity → generated `ItemResponse` 매퍼.
 *
 * ARCH-001/002: 매퍼를 controller 에서 추출. 도메인 enum (PriceType / Rarity / Layout)
 * 의 `.name` 을 generated enum 의 `forValue` 로 변환하는데, ADR-019 의 spec drift
 * 가드 (warn log + fail-fast) 를 적용 — DB 의 enum 값이 spec 의 enum 에 없으면
 * 운영 대시보드/Sentry 가 즉시 감지.
 *
 * cf. `LevelController.mapRewardTypeOrLogDrift` 와 동일 패턴이지만 nullable enum
 * 이 아니라 required 라 `getOrThrow()` 로 fail-fast (ADR-019 §1 + §2 결합).
 */
internal object ItemMapper {
    private val log = LoggerFactory.getLogger(ItemMapper::class.java)

    fun toApi(item: Item): ItemResponse =
        ItemResponse(
            id = item.id,
            slug = item.slug,
            name = item.name,
            description = item.description,
            categoryId = item.category?.id,
            categoryName = item.category?.name,
            priceType = mapPriceTypeOrFailFast(item.id, item.priceType.name),
            priceAmount = item.priceAmount,
            tokenPrice = item.tokenPrice,
            rarity = mapRarityOrFailFast(item.id, item.rarity.name),
            assetUrl = item.assetUrl,
            layout = mapLayoutOrFailFast(item.id, item.layout.name),
            isAnimated = item.isAnimated,
        )

    private fun mapPriceTypeOrFailFast(
        itemId: Long,
        priceType: String,
    ): ItemResponse.PriceType =
        runCatching { ItemResponse.PriceType.forValue(priceType) }
            .onFailure {
                log.warn(
                    "spec drift: item={} price_type='{}' is not in OpenAPI spec — failing fast (expected one of: {})",
                    itemId,
                    priceType,
                    ItemResponse.PriceType.entries.joinToString { it.value },
                )
            }.getOrThrow()

    private fun mapRarityOrFailFast(
        itemId: Long,
        rarity: String,
    ): ItemResponse.Rarity =
        runCatching { ItemResponse.Rarity.forValue(rarity) }
            .onFailure {
                log.warn(
                    "spec drift: item={} rarity='{}' is not in OpenAPI spec — failing fast (expected one of: {})",
                    itemId,
                    rarity,
                    ItemResponse.Rarity.entries.joinToString { it.value },
                )
            }.getOrThrow()

    private fun mapLayoutOrFailFast(
        itemId: Long,
        layout: String,
    ): ItemResponse.Layout =
        runCatching { ItemResponse.Layout.forValue(layout) }
            .onFailure {
                log.warn(
                    "spec drift: item={} layout='{}' is not in OpenAPI spec — failing fast (expected one of: {})",
                    itemId,
                    layout,
                    ItemResponse.Layout.entries.joinToString { it.value },
                )
            }.getOrThrow()
}
