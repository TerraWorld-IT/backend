package com.terraworld.api.wallet

import com.terraworld.domain.category.Category
import com.terraworld.domain.user.User
import com.terraworld.domain.wallet.WalletTransaction
import com.terraworld.domain.wallet.WalletTransactionRepository
import org.springframework.stereotype.Service

/**
 * N2 (구현 계획서 v4, 2026-05-21): wallet_transactions append-only 원장 연결.
 *
 * 변경 사유: `wallet_transactions` 테이블 + entity (V1, WalletTransaction.kt) 는 존재하나
 * 어떤 service 도 write 하지 않아 append-only ledger 가 비어 있었음. OpenAPI spec
 * (`openapi/spec/paths/records.yaml:73`) 은 record 생성 시 `wallet_transactions INSERT x2`
 * (BASIC_COIN + CATEGORY_TOKEN) 를 명시.
 *
 * 본 service 는 모든 재화 변동 지점 (record reward / ad reward / attendance / purchase /
 * exchange) 에서 호출되어 ledger row 를 기록. **호출자의 `@Transactional` 안에서 실행**되어야
 * 재화 변동과 ledger insert 가 atomic (별도 @Transactional 두지 않음 — propagation REQUIRED 로
 * 호출자 tx 에 참여).
 */
@Service
class WalletTransactionService(
    private val walletTransactionRepository: WalletTransactionRepository,
) {
    companion object {
        // currency_type (VARCHAR 30)
        const val CURRENCY_BASIC_COIN = "BASIC_COIN"
        const val CURRENCY_SPECIAL_COIN = "SPECIAL_COIN"
        const val CURRENCY_CATEGORY_TOKEN = "CATEGORY_TOKEN"

        // reason (VARCHAR 50)
        const val REASON_RECORD_REWARD = "RECORD_REWARD"
        const val REASON_AD_REWARD = "AD_REWARD"
        const val REASON_ATTENDANCE = "ATTENDANCE"
        const val REASON_PURCHASE = "PURCHASE"
        const val REASON_EXCHANGE = "EXCHANGE"
        const val REASON_INVITE_ACCEPT = "INVITE_ACCEPT"
    }

    /**
     * 재화 변동 1건을 ledger 에 append.
     *
     * @param amount 변동량 (획득 = 양수, 차감 = 음수)
     * @param balanceAfter 변동 후 잔액 (해당 currency 의 새 balance — 호출자가 mutation 후 값을 전달)
     * @param category CATEGORY_TOKEN 일 때만 비-null (어느 카테고리 토큰인지)
     * @param referenceId 원천 entity id (record id / ad log id / attendance log id / item id 등)
     */
    fun append(
        user: User,
        currencyType: String,
        amount: Long,
        balanceAfter: Long,
        reason: String,
        category: Category? = null,
        referenceId: Long? = null,
    ): WalletTransaction =
        walletTransactionRepository.save(
            WalletTransaction(
                user = user,
                currencyType = currencyType,
                category = category,
                amount = amount,
                balanceAfter = balanceAfter,
                reason = reason,
                referenceId = referenceId,
            ),
        )
}
