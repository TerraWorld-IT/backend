package com.terraworld.api.grant

import com.terraworld.api.currency.CurrencyService
import com.terraworld.api.wallet.WalletTransactionService
import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.domain.character.CharacterDefRepository
import com.terraworld.domain.character.UserCharacterRepository
import com.terraworld.domain.grant.UserGrantRepository
import com.terraworld.domain.item.ItemRepository
import com.terraworld.domain.item.UserItemRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

enum class GrantType { CURRENCY, SPIRIT, ITEM, BACKGROUND }

/**
 * 정령 캐릭터 코드(character_defs.code) ↔ 정령 아이템 slug(items.slug) 매핑 (아프젝 v2 §R3).
 * 정령 소유권 SoT = items(`{code}-spirit`, layout FIGURE, purchasable=false). 티어 해금 정령 지급·키우기 완료 지급이 같은 경로.
 */
object SpiritItems {
    private const val SUFFIX = "-spirit"

    /** 캐릭터 코드 → 정령 아이템 slug (예: cat → cat-spirit). */
    fun slugForCharacter(characterCode: String): String = characterCode + SUFFIX

    /** 정령 아이템 slug → 캐릭터 코드 (정령 아이템이 아니면 null). */
    fun characterForSlug(slug: String): String? = if (slug.endsWith(SUFFIX)) slug.removeSuffix(SUFFIX) else null
}

/**
 * 다형 지급 계약 (낙서장 리팩토링 P0-4/P0b). 티어해금→정령·육성→정령·이벤트→캐릭터·화폐 지급을 통합.
 *
 * - **멱등**: `(userId, idempotencyKey)` UNIQUE + native ON CONFLICT(예외 미발생 → rollback-only 오염 없음).
 *   key 는 호출자가 `actor+operation+payload-hash` 로 구성.
 * - **원자성**: @Transactional — grant 기록 + executor(credit / 캐릭터·아이템 지급)가 한 트랜잭션.
 * - executor 분기: CURRENCY → CurrencyService.credit / SPIRIT → user_characters 멱등 지급 /
 *   ITEM → user_items 멱등 소유 부여(아프젝 v2 — ref = item slug, 이미 보유면 수량 증가 없이 no-op) /
 *   BACKGROUND → 미연결(후속).
 *
 * @return true = 신규 지급, false = 이미 지급됨(멱등 no-op)
 */
@Service
class GrantService(
    private val userGrantRepository: UserGrantRepository,
    private val currencyService: CurrencyService,
    private val characterDefRepository: CharacterDefRepository,
    private val userCharacterRepository: UserCharacterRepository,
    private val itemRepository: ItemRepository,
    private val userItemRepository: UserItemRepository,
) {
    @Transactional
    fun grant(
        userId: String,
        type: GrantType,
        ref: String,
        amount: Long,
        idempotencyKey: String,
        source: String,
    ): Boolean {
        val inserted = userGrantRepository.insertIfAbsent(userId, type.name, ref, amount, idempotencyKey, source)
        if (inserted == 0) return false // 멱등 no-op — 예외 없음, tx clean

        when (type) {
            GrantType.CURRENCY ->
                currencyService.credit(
                    userId = userId,
                    currencyCode = ref,
                    amount = amount,
                    reason = WalletTransactionService.REASON_GRANT,
                    refType = "GRANT",
                    refKey = idempotencyKey,
                )

            GrantType.SPIRIT -> {
                // FK 위반(rollback-only) 회피 위해 존재 검증 선행 후 멱등 지급
                if (!characterDefRepository.existsById(ref)) throw BusinessException(ErrorCode.INVALID_INPUT)
                userCharacterRepository.insertIfAbsent(userId, ref, source)
            }

            GrantType.ITEM -> {
                // 아프젝 v2: ref = item slug. 미존재 slug 는 config 오류(fail-fast) — 시드 누락을 조용히 삼키지 않는다.
                val item =
                    itemRepository.findBySlug(ref).orElseThrow {
                        BusinessException(ErrorCode.INVALID_INPUT, "존재하지 않는 아이템 slug: $ref")
                    }
                // 이미 보유면 0 — 수량 증가 없이 no-op (grant 원장은 남는다: 같은 키 재호출은 위에서 차단).
                userItemRepository.insertIfAbsent(userId, item.id)
            }

            GrantType.BACKGROUND -> {
                // TODO(P3): user_backgrounds 소유 upsert 연결
            }
        }
        return true
    }
}
