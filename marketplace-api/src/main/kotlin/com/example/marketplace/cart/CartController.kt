package com.example.marketplace.cart

import com.example.marketplace.cart.dto.AddCartItemRequest
import com.example.marketplace.cart.dto.CartResponse
import com.example.marketplace.cart.dto.UpdateCartItemRequest
import com.example.marketplace.common.CommonResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@Tag(name = "Cart", description = "장바구니 API")
@RestController
@RequestMapping("/api/v1/cart")
@PreAuthorize("hasRole('BUYER')")
class CartController(private val cartService: CartService) {

    @Operation(summary = "장바구니 조회")
    @GetMapping
    fun getCart(
        @AuthenticationPrincipal memberId: Long
    ): CommonResponse<CartResponse> {
        return CommonResponse.success(cartService.getCart(memberId))
    }

    @Operation(summary = "장바구니에 상품 추가")
    @PostMapping("/items")
    fun addItem(
        @AuthenticationPrincipal memberId: Long,
        @Valid @RequestBody req: AddCartItemRequest
    ): CommonResponse<CartResponse> {
        return CommonResponse.success(cartService.addItem(memberId, req))
    }

    @Operation(summary = "장바구니 상품 수량 변경")
    @PatchMapping("/items/{productId}")
    fun updateItemQuantity(
        @AuthenticationPrincipal memberId: Long,
        @PathVariable productId: Long,
        @Valid @RequestBody req: UpdateCartItemRequest
    ): CommonResponse<CartResponse> {
        return CommonResponse.success(cartService.updateItemQuantity(memberId, productId, req))
    }

    @Operation(summary = "장바구니에서 상품 삭제")
    @DeleteMapping("/items/{productId}")
    fun removeItem(
        @AuthenticationPrincipal memberId: Long,
        @PathVariable productId: Long
    ): CommonResponse<CartResponse> {
        return CommonResponse.success(cartService.removeItem(memberId, productId))
    }

    @Operation(summary = "장바구니 비우기")
    @DeleteMapping
    fun clearCart(
        @AuthenticationPrincipal memberId: Long
    ): CommonResponse<CartResponse> {
        return CommonResponse.success(cartService.clearCart(memberId))
    }
}
