package com.example.marketplace.checkout

import com.example.marketplace.checkout.dto.CheckoutRequest
import com.example.marketplace.checkout.dto.CheckoutResponse
import com.example.marketplace.common.CommonResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@Tag(name = "Checkout", description = "결제 API")
@RestController
@RequestMapping("/api/v1")
@PreAuthorize("hasRole('BUYER')")
class CheckoutController(private val checkoutService: CheckoutService) {

    @Operation(summary = "결제 (장바구니 -> 주문 생성)")
    @PostMapping("/checkout")
    fun checkout(
        @AuthenticationPrincipal buyerId: Long,
        @Valid @RequestBody req: CheckoutRequest
    ): CommonResponse<CheckoutResponse> {
        return CommonResponse.success(checkoutService.checkout(buyerId, req))
    }
}
