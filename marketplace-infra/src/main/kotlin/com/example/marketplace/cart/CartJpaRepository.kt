package com.example.marketplace.cart

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.util.*

interface CartJpaRepository : JpaRepository<Cart, Long> {
    fun findByMemberId(memberId: Long): Optional<Cart>

    @Query("SELECT c FROM Cart c LEFT JOIN FETCH c.cartItems ci LEFT JOIN FETCH ci.product WHERE c.member.id = :memberId")
    fun findByMemberIdWithItems(memberId: Long): Optional<Cart>
}
