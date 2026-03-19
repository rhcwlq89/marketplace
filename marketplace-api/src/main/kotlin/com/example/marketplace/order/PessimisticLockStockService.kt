package com.example.marketplace.order

import com.example.marketplace.common.BusinessException
import com.example.marketplace.common.ErrorCode
import com.example.marketplace.product.ProductJpaRepository
import com.example.marketplace.product.ProductStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class PessimisticLockStockService(
    private val productJpaRepository: ProductJpaRepository
) {

    @Transactional
    fun decreaseStock(productId: Long, quantity: Int) {
        val product = productJpaRepository.findByIdWithLock(productId)
            .orElseThrow { BusinessException(ErrorCode.PRODUCT_NOT_FOUND) }

        if (product.status != ProductStatus.ON_SALE) {
            throw BusinessException(ErrorCode.PRODUCT_NOT_FOUND)
        }

        product.decreaseStock(quantity)
    }
}
