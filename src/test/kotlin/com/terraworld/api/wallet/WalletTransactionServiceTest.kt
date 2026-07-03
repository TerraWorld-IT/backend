package com.terraworld.api.wallet

import com.terraworld.domain.category.Category
import com.terraworld.domain.user.User
import com.terraworld.domain.wallet.WalletTransaction
import com.terraworld.domain.wallet.WalletTransactionRepository
import com.terraworld.test.FakeJpaRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * WalletTransactionService 의 append-only 원장 동작 커버.
 * - 단일 append (RECORD/AD/ATTENDANCE reason) row 저장 + 필드 정확성
 * - CATEGORY_TOKEN 일 때 category / referenceId 기록
 * - 차감(음수 amount) + balanceAfter 정확성
 * - 연속 append 가 append-only 로 누적 (덮어쓰기 없음)
 */
class WalletTransactionServiceTest {
    private lateinit var txRepo: FakeWalletTransactionRepository
    private lateinit var service: WalletTransactionService

    private lateinit var user: User
    private lateinit var walkCategory: Category

    @BeforeEach
    fun setup() {
        txRepo = FakeWalletTransactionRepository()
        service = WalletTransactionService(txRepo)

        user = User(id = "user-1", nickname = "테스터")
        walkCategory = Category(id = 1L, name = "산책", tokenName = "산책토큰")
    }

    @Test
    fun `append — RECORD_REWARD 획득 1건이 ledger 에 정확히 기록된다`() {
        val saved =
            service.append(
                user = user,
                currencyType = WalletTransactionService.CURRENCY_BASIC_COIN,
                amount = 20,
                balanceAfter = 20,
                reason = WalletTransactionService.REASON_RECORD_REWARD,
                referenceId = 42L,
            )

        assertEquals(1, txRepo.count())
        assertEquals("user-1", saved.user.id)
        assertEquals(WalletTransactionService.CURRENCY_BASIC_COIN, saved.currencyType)
        assertEquals(20, saved.amount)
        assertEquals(20, saved.balanceAfter)
        assertEquals(WalletTransactionService.REASON_RECORD_REWARD, saved.reason)
        assertEquals(42L, saved.referenceId)
        // BASIC_COIN 은 카테고리 무관 — null
        assertNull(saved.category)
    }

    @Test
    fun `append — CATEGORY_TOKEN 은 category 와 referenceId 를 함께 기록한다`() {
        val saved =
            service.append(
                user = user,
                currencyType = WalletTransactionService.CURRENCY_CATEGORY_TOKEN,
                amount = 10,
                balanceAfter = 10,
                reason = WalletTransactionService.REASON_RECORD_REWARD,
                category = walkCategory,
                referenceId = 7L,
            )

        assertEquals(WalletTransactionService.CURRENCY_CATEGORY_TOKEN, saved.currencyType)
        assertEquals(1L, saved.category?.id)
        assertEquals(10, saved.amount)
        assertEquals(10, saved.balanceAfter)
        assertEquals(7L, saved.referenceId)
    }

    @Test
    fun `append — 차감(음수 amount)도 balanceAfter 를 그대로 기록한다`() {
        // 잔액 100 에서 30 차감 → balanceAfter 70
        val saved =
            service.append(
                user = user,
                currencyType = WalletTransactionService.CURRENCY_SPECIAL_COIN,
                amount = -30,
                balanceAfter = 70,
                reason = WalletTransactionService.REASON_PURCHASE,
                referenceId = 99L,
            )

        assertEquals(-30, saved.amount)
        assertEquals(70, saved.balanceAfter)
        assertEquals(WalletTransactionService.REASON_PURCHASE, saved.reason)
    }

    @Test
    fun `append — AD 와 ATTENDANCE 연속 기록이 append-only 로 누적된다`() {
        // 잔액 0 → AD +5 → ATTENDANCE +3, 각 시점 balanceAfter 누적
        service.append(
            user = user,
            currencyType = WalletTransactionService.CURRENCY_BASIC_COIN,
            amount = 5,
            balanceAfter = 5,
            reason = WalletTransactionService.REASON_AD_REWARD,
            referenceId = 1L,
        )
        service.append(
            user = user,
            currencyType = WalletTransactionService.CURRENCY_BASIC_COIN,
            amount = 3,
            balanceAfter = 8,
            reason = WalletTransactionService.REASON_ATTENDANCE,
            referenceId = 2L,
        )

        // 덮어쓰기 없이 2 row 누적 (append-only)
        val all = txRepo.all()
        assertEquals(2, all.size)
        assertEquals(WalletTransactionService.REASON_AD_REWARD, all[0].reason)
        assertEquals(5, all[0].balanceAfter)
        assertEquals(WalletTransactionService.REASON_ATTENDANCE, all[1].reason)
        assertEquals(8, all[1].balanceAfter)
        // 두 row 의 id 는 서로 달라야 함 (별개 ledger entry)
        assertEquals(2, all.map { it.id }.toSet().size)
    }

    @Test
    fun `append — EXCHANGE reason 도 그대로 기록되며 row 가 누적된다`() {
        service.append(
            user = user,
            currencyType = WalletTransactionService.CURRENCY_BASIC_COIN,
            amount = 100,
            balanceAfter = 100,
            reason = WalletTransactionService.REASON_EXCHANGE,
        )

        assertEquals(1, txRepo.count())
        val row = txRepo.all().first()
        assertEquals(WalletTransactionService.REASON_EXCHANGE, row.reason)
        // referenceId 미전달 시 null
        assertNull(row.referenceId)
    }

    // ─── Fakes ─────────────────────────────────────────────────

    private class FakeWalletTransactionRepository :
        FakeJpaRepository<WalletTransaction, Long>(),
        WalletTransactionRepository {
        private var seq = 0L

        override fun extractId(entity: WalletTransaction): Long = entity.id

        // @GeneratedValue(IDENTITY) — id 기본값 0 이면 새 시퀀스 id 부여.
        override fun assignId(entity: WalletTransaction): WalletTransaction =
            if (entity.id == 0L) {
                WalletTransaction(
                    id = ++seq,
                    user = entity.user,
                    currencyType = entity.currencyType,
                    category = entity.category,
                    amount = entity.amount,
                    balanceAfter = entity.balanceAfter,
                    reason = entity.reason,
                    referenceId = entity.referenceId,
                    createdAt = entity.createdAt,
                )
            } else {
                entity
            }
    }
}
