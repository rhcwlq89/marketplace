package com.example.marketplace.order.event

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

@Component
class OrderEventListener {

    private val log = LoggerFactory.getLogger(javaClass)

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun handleOrderCreated(event: OrderCreatedEvent) {
        log.info("[NOTIFICATION] New order #{} created. Notifying seller #{}", event.orderId, event.sellerId)
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun handleOrderStatusChanged(event: OrderStatusChangedEvent) {
        log.info(
            "[NOTIFICATION] Order #{} status changed to {}. Notifying buyer #{}",
            event.orderId, event.newStatus, event.buyerId
        )
    }
}
