package com.example.marketplace.product

import com.example.marketplace.category.Category
import com.example.marketplace.common.BaseEntity
import com.example.marketplace.common.BusinessException
import com.example.marketplace.common.ErrorCode
import com.example.marketplace.member.Member
import jakarta.persistence.*
import org.hibernate.annotations.BatchSize
import java.math.BigDecimal

@Entity
@Table(name = "products")
class Product(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "seller_id", nullable = false)
    var seller: Member,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    var category: Category? = null,

    @Column(nullable = false)
    var name: String,

    @Column(columnDefinition = "TEXT")
    var description: String? = null,

    @Column(nullable = false, precision = 12, scale = 2)
    var price: BigDecimal,

    @Column(nullable = false)
    var stockQuantity: Int = 0,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: ProductStatus = ProductStatus.DRAFT,

    var salesCount: Int = 0,

    @BatchSize(size = 100)
    @OneToMany(mappedBy = "product", cascade = [CascadeType.ALL], orphanRemoval = true)
    var images: MutableList<ProductImage> = mutableListOf(),

    @Version
    var version: Long = 0
) : BaseEntity() {
    fun update(name: String?, description: String?, price: BigDecimal?, stockQuantity: Int?, status: ProductStatus?, category: Category?) {
        name?.let { this.name = it }
        description?.let { this.description = it }
        price?.let { this.price = it }
        stockQuantity?.let { this.stockQuantity = it }
        status?.let { this.status = it }
        category?.let { this.category = it }
    }

    fun decreaseStock(quantity: Int) {
        if (this.stockQuantity < quantity) {
            throw BusinessException(ErrorCode.INSUFFICIENT_STOCK)
        }
        this.stockQuantity -= quantity
        this.salesCount += quantity
        if (this.stockQuantity == 0) {
            this.status = ProductStatus.SOLD_OUT
        }
    }

    fun restoreStock(quantity: Int) {
        this.stockQuantity += quantity
        this.salesCount -= quantity
        if (this.status == ProductStatus.SOLD_OUT && this.stockQuantity > 0) {
            this.status = ProductStatus.ON_SALE
        }
    }

    fun addImage(image: ProductImage) {
        if (images.size >= 5) {
            throw BusinessException(ErrorCode.MAX_IMAGES_EXCEEDED)
        }
        images.add(image)
        image.product = this
    }
}
