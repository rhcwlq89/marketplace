package com.example.marketplace.order

import com.example.marketplace.common.CommonResponse
import com.example.marketplace.order.dto.CreateOrderRequest
import com.example.marketplace.order.dto.OrderResponse
import com.example.marketplace.order.dto.UpdateOrderStatusRequest
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.web.PageableDefault
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@Tag(name = "Orders", description = "주문 API")
@RestController
@RequestMapping("/api/v1")
class OrderController(private val orderService: OrderService) {

    @Operation(summary = "주문 생성 (구매자)")
    @PreAuthorize("hasRole('BUYER')")
    @PostMapping("/orders")
    fun createOrder(
        @AuthenticationPrincipal buyerId: Long,
        @Valid @RequestBody req: CreateOrderRequest
    ): CommonResponse<OrderResponse> {
        return CommonResponse.success(orderService.createOrder(buyerId, req))
    }

    @Operation(summary = "내 주문 목록")
    @GetMapping("/orders")
    fun getMyOrders(
        @AuthenticationPrincipal memberId: Long,
        @PageableDefault(size = 20, sort = ["createdAt"], direction = Sort.Direction.DESC) pageable: Pageable
    ): CommonResponse<Page<OrderResponse>> {
        return CommonResponse.success(orderService.getMyOrders(memberId, pageable))
    }

    @Operation(summary = "주문 상세 조회")
    @GetMapping("/orders/{orderId}")
    fun getOrder(
        @AuthenticationPrincipal memberId: Long,
        @PathVariable orderId: Long
    ): CommonResponse<OrderResponse> {
        return CommonResponse.success(orderService.getOrder(memberId, orderId))
    }

    @Operation(summary = "주문 취소 (구매자)")
    @PreAuthorize("hasRole('BUYER')")
    @PostMapping("/orders/{orderId}/cancel")
    fun cancelOrder(
        @AuthenticationPrincipal buyerId: Long,
        @PathVariable orderId: Long
    ): CommonResponse<OrderResponse> {
        return CommonResponse.success(orderService.cancelOrder(buyerId, orderId))
    }

    @Operation(summary = "판매자 주문 목록")
    @PreAuthorize("hasRole('SELLER')")
    @GetMapping("/sellers/orders")
    fun getSellerOrders(
        @AuthenticationPrincipal sellerId: Long,
        @PageableDefault(size = 20, sort = ["createdAt"], direction = Sort.Direction.DESC) pageable: Pageable
    ): CommonResponse<Page<OrderResponse>> {
        return CommonResponse.success(orderService.getSellerOrders(sellerId, pageable))
    }

    @Operation(summary = "배송 상태 변경 (판매자)")
    @PreAuthorize("hasRole('SELLER')")
    @PatchMapping("/sellers/orders/{orderId}/status")
    fun updateOrderStatus(
        @AuthenticationPrincipal sellerId: Long,
        @PathVariable orderId: Long,
        @Valid @RequestBody req: UpdateOrderStatusRequest
    ): CommonResponse<OrderResponse> {
        return CommonResponse.success(orderService.updateOrderStatus(sellerId, orderId, req))
    }
}
