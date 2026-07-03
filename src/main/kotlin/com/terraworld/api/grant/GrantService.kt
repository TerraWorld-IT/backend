package com.terraworld.api.grant

import com.terraworld.api.currency.CurrencyService
import com.terraworld.api.wallet.WalletTransactionService
import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.domain.character.CharacterDefRepository
import com.terraworld.domain.character.UserCharacterRepository
import com.terraworld.domain.grant.UserGrantRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

enum class GrantType { CURRENCY, SPIRIT, ITEM, BACKGROUND }

/**
 * 다형 지급 계약 (낙서장 리팩토링 P0-4/P0b). 티어해금→정령·육성→정령·이벤트→캐릭터·화폐 지급을 통합.
 *
 * - **멱등**: `(userId, idempotencyKey)` UNIQUE + native ON CONFLICT(예외 미발생 → rollback-only 오염 없음).
 *   key 는 호출자가 `actor+operation+payload-hash` 로 구성.
 * - **원자성**: @Transactional — grant 기록 + executor(credit / 캐릭터 지급)가 한 트랜잭션.
 * - executor 분기: CURRENCY → CurrencyService.credit / SPIRIT → user_characters 멱등 지급 /
 *   ITEM·BACKGROUND → P3(user_items/background) 연결 예정.
 *
 * ⚠️ **half-state fence (P1 read-cutover 전)**: CURRENCY grant 는 신 substrate(user_currency_balances)에
 * credit 하지만 지갑 read(WalletBuilder)는 아직 구 저장을 읽는다 → P1 read-cutover 이전에는 실 화폐 발행
 * 경로에 wiring 금지(split-brain). SPIRIT 는 수신처(user_characters) 존재하므로 P0b 부터 사용 가능.
 *
 * @return true = 신규 지급, false = 이미 지급됨(멱등 no-op)
 */
@Service
class GrantService(
    private val userGrantRepository: UserGrantRepository,
    private val currencyService: CurrencyService,
    private val characterDefRepository: CharacterDefRepository,
    private val userCharacterRepository: UserCharacterRepository,
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

            GrantType.ITEM, GrantType.BACKGROUND -> {
                // TODO(P3): user_items / user_backgrounds 소유 upsert 연결
            }
        }
        return true
    }
}
