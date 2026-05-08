package com.terraworld.api.purchase

import com.terraworld.security.SecurityUtil
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import io.terraworld.api.api.PurchaseApi
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import com.terraworld.api.purchase.dto.PurchaseRequest as LocalPurchaseRequest
import io.terraworld.api.model.PurchaseRequest as ApiPurchaseRequest
import io.terraworld.api.model.PurchaseResponse as ApiPurchaseResponse

/**
 * PurchaseApi (1 endpoint — purchaseItem) implement. spec PR #12 의 tag
 * 분리 (Shop ↔ Purchase) 결과 PurchaseApi 신규 생성됨.
 *
 * ARCH-008-phase-7 후: PurchaseService 가 generated [ApiPurchaseResponse] 직접 반환.
 * controller 매퍼 제거. local PurchaseRequest 만 잔존 (request DTO 는 별도 phase).
 */
@Tag(name = "Purchase", description = "구매 API")
@RestController
@RequestMapping("/api/v1")
class PurchaseController(
    private val purchaseService: PurchaseService,
) : PurchaseApi {
    @Operation(
        summary = "아이템 구매",
        description = "재화를 차감하고 아이템을 보유 목록에 추가합니다",
    )
    override fun purchaseItem(
        @Valid purchaseRequest: ApiPurchaseRequest,
    ): ResponseEntity<ApiPurchaseResponse> {
        val userId = SecurityUtil.getCurrentUserId()
        val response =
            purchaseService.purchase(
                userId = userId,
                request = LocalPurchaseRequest(itemId = purchaseRequest.itemId),
            )
        return ResponseEntity.ok(response)
    }
}
