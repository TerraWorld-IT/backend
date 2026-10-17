package com.terraworld.api.growth

import com.terraworld.api.grant.SpiritItems
import com.terraworld.domain.character.CharacterDefRepository
import com.terraworld.domain.item.ItemRepository
import com.terraworld.domain.item.UserItemRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import kotlin.random.Random

/** 활성 키우기 정령 중 미보유 종을 우선 공개한다. 소유권 기준은 아이템이다. */
@Component
class SpiritPool(
    private val characterDefRepository: CharacterDefRepository,
    private val itemRepository: ItemRepository,
    private val userItemRepository: UserItemRepository,
    private val random: Random = Random.Default,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun choose(userId: String): String {
        val activeSlugs = itemRepository.findAllByIsActiveTrue().mapNotNull { it.slug }.toSet()
        val pool =
            characterDefRepository
                .findAllByOrderBySortOrderAsc()
                .filter { it.acquireSource == "GROW" && SpiritItems.slugForCharacter(it.code) in activeSlugs }
                .map { it.code }
        if (pool.isEmpty()) {
            log.warn("growth.spirit.pool.empty user={} — 고양이 정령으로 공개", userId)
            return "cat"
        }
        val owned = userItemRepository.findAllByUserId(userId).mapNotNull { it.item.slug }.toSet()
        val candidates = pool.filter { SpiritItems.slugForCharacter(it) !in owned }.ifEmpty { pool }
        return candidates.random(random)
    }
}
