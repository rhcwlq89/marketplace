package com.example.marketplace.fcfs.dto

data class FcfsOrderResponse(
    val orderId: Long,
    val status: String,
    val message: String? = null
)
