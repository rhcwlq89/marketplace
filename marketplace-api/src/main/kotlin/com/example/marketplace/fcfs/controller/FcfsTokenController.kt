package com.example.marketplace.fcfs.controller

import com.example.marketplace.fcfs.dto.FcfsOrderRequest
import com.example.marketplace.fcfs.dto.FcfsOrderResponse
import com.example.marketplace.fcfs.dto.FcfsTokenResponse
import com.example.marketplace.fcfs.service.FcfsTokenService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
class FcfsTokenController(
    private val fcfsTokenService: FcfsTokenService
) {

    @PostMapping("/api/tokens/issue")
    fun issueToken(@RequestBody request: FcfsOrderRequest): ResponseEntity<FcfsTokenResponse> {
        val response = fcfsTokenService.issueToken(request.productId, request.userId)
        return if (response != null) {
            ResponseEntity.ok(response)
        } else {
            ResponseEntity.status(409).build()
        }
    }

    @PostMapping("/api/orders/token")
    fun purchaseWithToken(
        @RequestHeader("Authorization") authorization: String,
        @RequestBody body: Map<String, Int>
    ): ResponseEntity<FcfsOrderResponse> {
        val token = authorization.removePrefix("Bearer ").trim()
        val quantity = body["quantity"] ?: 1

        val response = fcfsTokenService.purchaseWithToken(token, quantity)
        return if (response.status == "SUCCESS") {
            ResponseEntity.ok(response)
        } else {
            ResponseEntity.status(409).body(response)
        }
    }
}
