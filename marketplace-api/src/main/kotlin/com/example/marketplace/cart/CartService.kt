package com.example.marketplace.cart

import com.example.marketplace.cart.dto.AddCartItemRequest
import com.example.marketplace.cart.dto.CartResponse
import com.example.marketplace.cart.dto.UpdateCartItemRequest
import com.example.marketplace.common.BusinessException
import com.example.marketplace.common.DistributedLock
import com.example.marketplace.common.ErrorCode
import com.example.marketplace.member.MemberJpaRepository
import com.example.marketplace.product.ProductJpaRepository
import com.example.marketplace.product.ProductStatus
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class CartService(
    private val cartJpaRepository: CartJpaRepository,
    private val cartItemJpaRepository: CartItemJpaRepository,
    private val productJpaRepository: ProductJpaRepository,
    private val memberJpaRepository: MemberJpaRepository
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun getCart(memberId: Long): CartResponse {
        val cart = getOrCreateCart(memberId)
        return CartResponse.from(cart)
    }

    @Transactional
    @DistributedLock(key = "'cart:add:' + #memberId", waitTime = 5, leaseTime = 10)
    fun addItem(memberId: Long, req: AddCartItemRequest): CartResponse {
        val cart = getOrCreateCart(memberId)

        val product = productJpaRepository.findById(req.productId)
            .orElseThrow { BusinessException(ErrorCode.PRODUCT_NOT_FOUND) }

        // 자기 상품 담기 방지
        if (product.seller.id == memberId) {
            throw BusinessException(ErrorCode.CANNOT_ADD_OWN_PRODUCT)
        }

        // 판매 중인 상품만 담기 가능
        if (product.status != ProductStatus.ON_SALE) {
            throw BusinessException(ErrorCode.PRODUCT_NOT_AVAILABLE)
        }

        // 이미 담긴 상품인지 확인
        val existingItem = cart.findItemByProductId(req.productId)
        if (existingItem != null) {
            // 수량 추가
            val newQuantity = existingItem.quantity + req.quantity
            if (newQuantity > CartItem.MAX_QUANTITY) {
                throw BusinessException(ErrorCode.CART_QUANTITY_EXCEEDED)
            }
            existingItem.updateQuantity(newQuantity)
        } else {
            // 새 아이템 추가
            val cartItem = CartItem(
                product = product,
                quantity = req.quantity
            )
            cart.addItem(cartItem)
        }

        val savedCart = cartJpaRepository.save(cart)
        return CartResponse.from(savedCart)
    }

    @Transactional
    @DistributedLock(key = "'cart:update:' + #memberId + ':' + #productId", waitTime = 5, leaseTime = 10)
    fun updateItemQuantity(memberId: Long, productId: Long, req: UpdateCartItemRequest): CartResponse {
        val cart = cartJpaRepository.findByMemberIdWithItems(memberId)
            .orElseThrow { BusinessException(ErrorCode.CART_NOT_FOUND) }

        val cartItem = cart.findItemByProductId(productId)
            ?: throw BusinessException(ErrorCode.CART_ITEM_NOT_FOUND)

        cartItem.updateQuantity(req.quantity)

        val savedCart = cartJpaRepository.save(cart)
        return CartResponse.from(savedCart)
    }

    @Transactional
    fun removeItem(memberId: Long, productId: Long): CartResponse {
        val cart = cartJpaRepository.findByMemberIdWithItems(memberId)
            .orElseThrow { BusinessException(ErrorCode.CART_NOT_FOUND) }

        val cartItem = cart.findItemByProductId(productId)
            ?: throw BusinessException(ErrorCode.CART_ITEM_NOT_FOUND)

        cart.removeItem(cartItem)

        val savedCart = cartJpaRepository.save(cart)
        return CartResponse.from(savedCart)
    }

    @Transactional
    fun clearCart(memberId: Long): CartResponse {
        val cart = cartJpaRepository.findByMemberIdWithItems(memberId)
            .orElseThrow { BusinessException(ErrorCode.CART_NOT_FOUND) }

        cart.clear()

        val savedCart = cartJpaRepository.save(cart)
        return CartResponse.from(savedCart)
    }

    @Transactional
    fun getOrCreateCart(memberId: Long): Cart {
        return cartJpaRepository.findByMemberIdWithItems(memberId)
            .orElseGet {
                val member = memberJpaRepository.findById(memberId)
                    .orElseThrow { BusinessException(ErrorCode.MEMBER_NOT_FOUND) }
                cartJpaRepository.save(Cart(member = member))
            }
    }
}
