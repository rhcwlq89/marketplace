package com.example.marketplace.fcfs.dto

data class FcfsOrderRequest(
    val productId: Long,
    val userId: Long,
    val quantity: Int = 1
)
