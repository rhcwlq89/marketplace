package com.example.marketplace.config

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicInteger

@Configuration
class MetricsConfig {

    @Bean
    fun orderMetrics(meterRegistry: MeterRegistry): OrderMetrics {
        return OrderMetrics(meterRegistry)
    }

    @Bean
    fun productMetrics(meterRegistry: MeterRegistry): ProductMetrics {
        return ProductMetrics(meterRegistry)
    }
}

@Component
class OrderMetrics(private val meterRegistry: MeterRegistry) {

    private val orderCreatedCounter: Counter = Counter.builder("marketplace.orders.created")
        .description("Total number of orders created")
        .register(meterRegistry)

    private val orderCancelledCounter: Counter = Counter.builder("marketplace.orders.cancelled")
        .description("Total number of orders cancelled")
        .register(meterRegistry)

    private val orderFailedCounter: Counter = Counter.builder("marketplace.orders.failed")
        .description("Total number of failed order attempts")
        .register(meterRegistry)

    private val orderCreationTimer: Timer = Timer.builder("marketplace.orders.creation.time")
        .description("Time taken to create an order")
        .register(meterRegistry)

    private val activeOrders: AtomicInteger = AtomicInteger(0)

    init {
        meterRegistry.gauge("marketplace.orders.active", activeOrders)
    }

    fun incrementOrderCreated() {
        orderCreatedCounter.increment()
        activeOrders.incrementAndGet()
    }

    fun incrementOrderCancelled() {
        orderCancelledCounter.increment()
        activeOrders.decrementAndGet()
    }

    fun incrementOrderFailed() {
        orderFailedCounter.increment()
    }

    fun recordOrderCreationTime(timeMs: Long) {
        orderCreationTimer.record(java.time.Duration.ofMillis(timeMs))
    }

    fun <T> timeOrderCreation(block: () -> T): T {
        return orderCreationTimer.recordCallable(block)!!
    }
}

@Component
class ProductMetrics(private val meterRegistry: MeterRegistry) {

    private val productCreatedCounter: Counter = Counter.builder("marketplace.products.created")
        .description("Total number of products created")
        .register(meterRegistry)

    private val productViewCounter: Counter = Counter.builder("marketplace.products.views")
        .description("Total number of product views")
        .register(meterRegistry)

    private val productSearchCounter: Counter = Counter.builder("marketplace.products.searches")
        .description("Total number of product searches")
        .register(meterRegistry)

    private val stockDecreasedCounter: Counter = Counter.builder("marketplace.products.stock.decreased")
        .description("Total number of stock decrease operations")
        .register(meterRegistry)

    private val stockRestoredCounter: Counter = Counter.builder("marketplace.products.stock.restored")
        .description("Total number of stock restore operations")
        .register(meterRegistry)

    private val insufficientStockCounter: Counter = Counter.builder("marketplace.products.stock.insufficient")
        .description("Total number of insufficient stock errors")
        .register(meterRegistry)

    fun incrementProductCreated() {
        productCreatedCounter.increment()
    }

    fun incrementProductView() {
        productViewCounter.increment()
    }

    fun incrementProductSearch() {
        productSearchCounter.increment()
    }

    fun incrementStockDecreased() {
        stockDecreasedCounter.increment()
    }

    fun incrementStockRestored() {
        stockRestoredCounter.increment()
    }

    fun incrementInsufficientStock() {
        insufficientStockCounter.increment()
    }
}
