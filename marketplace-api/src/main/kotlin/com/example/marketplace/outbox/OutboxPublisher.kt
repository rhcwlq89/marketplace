package com.example.marketplace.outbox

import com.example.marketplace.config.KafkaConfig
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * Outbox 이벤트 저장 인터페이스
 * - docker/prod: Kafka로 발행될 이벤트를 DB에 저장
 * - local: 로깅만 수행 (No-op)
 */
interface OutboxEventService {
    fun saveEvent(aggregateType: String, aggregateId: String, eventType: String, payload: Any)
}

@Component
@Profile("docker", "prod")
class OutboxPublisher(
    private val outboxJpaRepository: OutboxJpaRepository,
    private val kafkaTemplate: KafkaTemplate<String, Any>,
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelay = 1000)
    @Transactional
    fun publishPendingEvents() {
        val pendingEvents = outboxJpaRepository.findRetryableEvents()

        pendingEvents.forEach { event ->
            try {
                val topic = determineTopicForEvent(event.eventType)
                val payload = objectMapper.readValue(event.payload, Map::class.java)

                kafkaTemplate.send(topic, event.aggregateId, payload)
                    .whenComplete { _, ex ->
                        if (ex == null) {
                            log.debug("Successfully published event: ${event.id} to topic: $topic")
                        } else {
                            log.error("Failed to publish event: ${event.id}", ex)
                        }
                    }

                event.markAsProcessed()
                outboxJpaRepository.save(event)

            } catch (e: Exception) {
                log.error("Failed to process outbox event: ${event.id}", e)
                event.markAsFailed(e.message ?: "Unknown error")
                outboxJpaRepository.save(event)
            }
        }
    }

    @Scheduled(cron = "0 0 * * * *")
    @Transactional
    fun cleanupProcessedEvents() {
        val cutoff = LocalDateTime.now().minusDays(7)
        val deleted = outboxJpaRepository.deleteProcessedEventsBefore(OutboxStatus.PROCESSED, cutoff)
        if (deleted > 0) {
            log.info("Cleaned up $deleted processed outbox events")
        }
    }

    private fun determineTopicForEvent(eventType: String): String {
        return when {
            eventType.contains("OrderCreated") -> KafkaConfig.ORDER_CREATED_TOPIC
            eventType.contains("OrderStatusChanged") -> KafkaConfig.ORDER_STATUS_CHANGED_TOPIC
            else -> KafkaConfig.OUTBOX_TOPIC
        }
    }
}

@Component
@Profile("docker", "prod")
class OutboxService(
    private val outboxJpaRepository: OutboxJpaRepository,
    private val objectMapper: ObjectMapper
) : OutboxEventService {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun saveEvent(aggregateType: String, aggregateId: String, eventType: String, payload: Any) {
        val event = OutboxEvent(
            aggregateType = aggregateType,
            aggregateId = aggregateId,
            eventType = eventType,
            payload = objectMapper.writeValueAsString(payload)
        )
        outboxJpaRepository.save(event)
        log.debug("Saved outbox event: $eventType for $aggregateType:$aggregateId")
    }
}

@Component
@Profile("local")
class NoOpOutboxService : OutboxEventService {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun saveEvent(aggregateType: String, aggregateId: String, eventType: String, payload: Any) {
        log.debug("NoOp outbox service - event would be saved: $eventType for $aggregateType:$aggregateId")
    }
}
