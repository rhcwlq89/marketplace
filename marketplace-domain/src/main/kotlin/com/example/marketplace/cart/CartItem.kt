package com.example.marketplace.cart

import com.example.marketplace.common.BaseEntity
import com.example.marketplace.common.BusinessException
import com.example.marketplace.common.ErrorCode
import com.example.marketplace.product.Product
import jakarta.persistence.*

@Entity
@Table(
    name = "cart_items",
    uniqueConstraints = [UniqueConstraint(columnNames = ["cart_id", "product_id"])]
)
class CartItem(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cart_id", nullable = false)
    var cart: Cart? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    var product: Product,

    @Column(nullable = false)
    var quantity: Int
) : BaseEntity() {

    companion object {
        const val MIN_QUANTITY = 1
        const val MAX_QUANTITY = 99
    }

    init {
        validateQuantity(quantity)
    }

    fun updateQuantity(newQuantity: Int) {
        validateQuantity(newQuantity)
        this.quantity = newQuantity
    }

    private fun validateQuantity(qty: Int) {
        if (qty < MIN_QUANTITY) {
            throw BusinessException(ErrorCode.INVALID_CART_QUANTITY)
        }
        if (qty > MAX_QUANTITY) {
            throw BusinessException(ErrorCode.CART_QUANTITY_EXCEEDED)
        }
    }
}
