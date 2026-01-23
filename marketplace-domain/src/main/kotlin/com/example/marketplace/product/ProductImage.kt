package com.example.marketplace.product

import com.example.marketplace.common.BaseEntity
import jakarta.persistence.*

@Entity
@Table(name = "product_images")
class ProductImage(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    var product: Product? = null,

    @Column(nullable = false)
    var imageUrl: String,

    var displayOrder: Int = 0
) : BaseEntity()
