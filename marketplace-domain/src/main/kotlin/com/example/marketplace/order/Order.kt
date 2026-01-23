package com.example.marketplace.order

import com.example.marketplace.common.BaseEntity
import com.example.marketplace.common.BusinessException
import com.example.marketplace.common.ErrorCode
import com.example.marketplace.member.Member
import jakarta.persistence.*
import org.hibernate.annotations.BatchSize
import java.math.BigDecimal
import java.util.UUID

@Entity
@Table(name = "orders")
class Order(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "buyer_id", nullable = false)
    var buyer: Member,

    @Column(nullable = false, unique = true)
    var orderNumber: String = UUID.randomUUID().toString().replace("-", "").substring(0, 16).uppercase(),

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: OrderStatus = OrderStatus.PENDING,

    @Column(precision = 12, scale = 2)
    var totalAmount: BigDecimal = BigDecimal.ZERO,

    @Embedded
    var shippingAddress: ShippingAddress = ShippingAddress(),

    @BatchSize(size = 100)
    @OneToMany(mappedBy = "order", cascade = [CascadeType.ALL], orphanRemoval = true)
    var orderItems: MutableList<OrderItem> = mutableListOf()
) : BaseEntity() {
    fun addItem(item: OrderItem) {
        orderItems.add(item)
        item.order = this
        calculateTotalAmount()
    }

    fun calculateTotalAmount() {
        this.totalAmount = orderItems.fold(BigDecimal.ZERO) { acc, item -> acc + item.subtotal }
    }

    fun cancel() {
        if (status != OrderStatus.PENDING && status != OrderStatus.CONFIRMED) {
            throw BusinessException(ErrorCode.CANNOT_CANCEL_ORDER)
        }
        this.status = OrderStatus.CANCELLED
    }

    fun updateStatus(newStatus: OrderStatus) {
        this.status = newStatus
    }

    fun canBeCancelled(): Boolean = status == OrderStatus.PENDING || status == OrderStatus.CONFIRMED
}
