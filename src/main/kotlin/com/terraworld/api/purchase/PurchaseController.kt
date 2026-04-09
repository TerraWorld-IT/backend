package com.terraworld.api.purchase

import com.terraworld.api.purchase.dto.PurchaseRequest
import com.terraworld.api.purchase.dto.PurchaseResponse
import com.terraworld.security.SecurityUtil
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.*

@Tag(name = "Purchase", description = "구매 API")
@RestController
@RequestMapping("/api/v1/purchases")
class PurchaseController(
    private val purchaseService: PurchaseService,
) {

    @Operation(summary = "아이템 구매", description = "재화를 차감하고 아이템을 보유 목록에 추가합니다")
    @PostMapping
    fun purchase(@Valid @RequestBody request: PurchaseRequest): PurchaseResponse =
        purchaseService.purchase(SecurityUtil.getCurrentUserId(), request)
}
