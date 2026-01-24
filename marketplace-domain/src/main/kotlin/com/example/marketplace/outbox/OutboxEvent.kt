package com.example.marketplace.outbox

import com.example.marketplace.common.BaseEntity
import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "outbox_events")
class OutboxEvent(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false)
    val aggregateType: String,

    @Column(nullable = false)
    val aggregateId: String,

    @Column(nullable = false)
    val eventType: String,

    @Column(nullable = false, columnDefinition = "TEXT")
    val payload: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: OutboxStatus = OutboxStatus.PENDING,

    @Column(nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),

    var processedAt: LocalDateTime? = null,

    @Column(nullable = false)
    var retryCount: Int = 0,

    var lastError: String? = null
) {
    fun markAsProcessed() {
        this.status = OutboxStatus.PROCESSED
        this.processedAt = LocalDateTime.now()
    }

    fun markAsFailed(error: String) {
        this.retryCount++
        this.lastError = error
        if (this.retryCount >= MAX_RETRY_COUNT) {
            this.status = OutboxStatus.FAILED
        }
    }

    companion object {
        const val MAX_RETRY_COUNT = 5
    }
}

enum class OutboxStatus {
    PENDING,
    PROCESSED,
    FAILED
}
