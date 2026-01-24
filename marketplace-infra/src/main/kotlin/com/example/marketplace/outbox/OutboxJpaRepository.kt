package com.example.marketplace.outbox

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.LocalDateTime

interface OutboxJpaRepository : JpaRepository<OutboxEvent, Long> {

    fun findByStatusOrderByCreatedAtAsc(status: OutboxStatus): List<OutboxEvent>

    @Query("SELECT e FROM OutboxEvent e WHERE e.status = :status ORDER BY e.createdAt ASC")
    fun findPendingEvents(status: OutboxStatus = OutboxStatus.PENDING): List<OutboxEvent>

    @Modifying
    @Query("DELETE FROM OutboxEvent e WHERE e.status = :status AND e.processedAt < :before")
    fun deleteProcessedEventsBefore(status: OutboxStatus = OutboxStatus.PROCESSED, before: LocalDateTime): Int

    @Query("SELECT e FROM OutboxEvent e WHERE e.status = :status AND e.retryCount < :maxRetries ORDER BY e.createdAt ASC")
    fun findRetryableEvents(
        status: OutboxStatus = OutboxStatus.PENDING,
        maxRetries: Int = OutboxEvent.MAX_RETRY_COUNT
    ): List<OutboxEvent>
}
