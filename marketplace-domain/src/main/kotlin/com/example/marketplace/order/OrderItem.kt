package com.example.marketplace.order

import com.example.marketplace.member.Member
import com.example.marketplace.product.Product
import jakarta.persistence.*
import java.math.BigDecimal

@Entity
@Table(name = "order_items")
class OrderItem(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    var order: Order? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    var product: Product,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "seller_id", nullable = false)
    var seller: Member,

    @Column(nullable = false)
    var productName: String,

    @Column(nullable = false, precision = 12, scale = 2)
    var productPrice: BigDecimal,

    @Column(nullable = false)
    var quantity: Int,

    @Column(nullable = false, precision = 12, scale = 2)
    var subtotal: BigDecimal = productPrice.multiply(BigDecimal(quantity))
)
