package com.terraworld.domain.wallet

import org.springframework.data.jpa.repository.JpaRepository

interface WalletTransactionRepository : JpaRepository<WalletTransaction, Long>
