package com.example.marketplace.fcfs.controller

import com.example.marketplace.fcfs.dto.FcfsOrderRequest
import com.example.marketplace.fcfs.dto.FcfsOrderResponse
import com.example.marketplace.fcfs.service.FcfsRedisService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class FcfsRedisController(
    private val fcfsRedisService: FcfsRedisService
) {

    @PostMapping("/api/orders/redis")
    fun purchase(@RequestBody request: FcfsOrderRequest): ResponseEntity<FcfsOrderResponse> {
        val response = fcfsRedisService.purchase(request)
        return if (response.status == "SUCCESS") {
            ResponseEntity.ok(response)
        } else {
            ResponseEntity.status(409).body(response)
        }
    }
}
