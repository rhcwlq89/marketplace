package com.example.marketplace.cart

import com.example.marketplace.common.BaseEntity
import com.example.marketplace.member.Member
import jakarta.persistence.*
import org.hibernate.annotations.BatchSize

@Entity
@Table(name = "carts")
class Cart(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false, unique = true)
    var member: Member,

    @BatchSize(size = 100)
    @OneToMany(mappedBy = "cart", cascade = [CascadeType.ALL], orphanRemoval = true)
    var cartItems: MutableList<CartItem> = mutableListOf()
) : BaseEntity() {

    fun addItem(cartItem: CartItem) {
        cartItems.add(cartItem)
        cartItem.cart = this
    }

    fun removeItem(cartItem: CartItem) {
        cartItems.remove(cartItem)
        cartItem.cart = null
    }

    fun clear() {
        cartItems.clear()
    }

    fun findItemByProductId(productId: Long): CartItem? {
        return cartItems.find { it.product.id == productId }
    }
}
