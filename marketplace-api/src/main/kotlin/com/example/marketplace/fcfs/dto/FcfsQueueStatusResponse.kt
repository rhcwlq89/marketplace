package com.example.marketplace.fcfs.dto

data class FcfsQueueStatusResponse(
    val status: String,
    val position: Long? = null
)
