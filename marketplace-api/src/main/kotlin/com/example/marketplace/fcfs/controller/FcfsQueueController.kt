package com.example.marketplace.fcfs.controller

import com.example.marketplace.fcfs.dto.FcfsOrderRequest
import com.example.marketplace.fcfs.dto.FcfsQueueStatusResponse
import com.example.marketplace.fcfs.service.FcfsQueueService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
class FcfsQueueController(
    private val fcfsQueueService: FcfsQueueService
) {

    @PostMapping("/api/queue/enter")
    fun enter(@RequestBody request: FcfsOrderRequest): ResponseEntity<FcfsQueueStatusResponse> {
        val response = fcfsQueueService.enter(request.productId, request.userId)
        return ResponseEntity.ok(response)
    }

    @GetMapping("/api/queue/status")
    fun status(
        @RequestParam productId: Long,
        @RequestParam userId: Long
    ): ResponseEntity<FcfsQueueStatusResponse> {
        val response = fcfsQueueService.getStatus(productId, userId)
        return ResponseEntity.ok(response)
    }
}
