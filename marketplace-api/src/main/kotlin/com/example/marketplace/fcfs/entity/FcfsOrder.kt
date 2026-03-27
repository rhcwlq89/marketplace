package com.example.marketplace.fcfs.entity

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "fcfs_orders")
class FcfsOrder(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false)
    val productId: Long,

    @Column(nullable = false)
    val userId: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val strategy: FcfsStrategy,

    @Column(nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now()
)

enum class FcfsStrategy {
    DB_LOCK, REDIS, QUEUE, TOKEN
}
