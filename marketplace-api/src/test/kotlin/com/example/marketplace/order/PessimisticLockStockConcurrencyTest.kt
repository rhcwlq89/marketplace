package com.example.marketplace.order

import com.example.marketplace.member.Member
import com.example.marketplace.member.MemberJpaRepository
import com.example.marketplace.member.Role
import com.example.marketplace.product.Product
import com.example.marketplace.product.ProductJpaRepository
import com.example.marketplace.product.ProductStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.math.BigDecimal
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

@SpringBootTest
@ActiveProfiles("local")
class PessimisticLockStockConcurrencyTest {

    @Autowired
    private lateinit var pessimisticLockStockService: PessimisticLockStockService

    @Autowired
    private lateinit var productJpaRepository: ProductJpaRepository

    @Autowired
    private lateinit var memberJpaRepository: MemberJpaRepository

    private lateinit var seller: Member
    private lateinit var product: Product

    @BeforeEach
    fun setup() {
        productJpaRepository.deleteAll()
        memberJpaRepository.deleteAll()

        seller = memberJpaRepository.save(
            Member(
                email = "seller@test.com",
                password = "password",
                role = Role.SELLER,
                businessNumber = "123-45-67890"
            )
        )

        product = productJpaRepository.save(
            Product(
                seller = seller,
                name = "한정판 스니커즈",
                price = BigDecimal("100000"),
                stockQuantity = 100,
                status = ProductStatus.ON_SALE
            )
        )
    }

    @Test
    @DisplayName("비관적 락: 100명이 동시에 1개씩 구매하면 재고가 정확히 0이 된다")
    fun pessimisticLock_100ConcurrentRequests_stockBecomesZero() {
        val threadCount = 100
        val executorService = Executors.newFixedThreadPool(32)
        val latch = CountDownLatch(threadCount)
        val successCount = AtomicInteger(0)
        val failCount = AtomicInteger(0)

        val startTime = System.currentTimeMillis()

        for (i in 1..threadCount) {
            executorService.submit {
                try {
                    pessimisticLockStockService.decreaseStock(product.id!!, 1)
                    successCount.incrementAndGet()
                } catch (e: Exception) {
                    failCount.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await()
        executorService.shutdown()

        val elapsed = System.currentTimeMillis() - startTime

        val updatedProduct = productJpaRepository.findById(product.id!!).get()

        println("=== 비관적 락 (FOR UPDATE) 동시성 테스트 결과 ===")
        println("동시 요청 수: $threadCount")
        println("성공: ${successCount.get()}")
        println("실패: ${failCount.get()}")
        println("최종 재고: ${updatedProduct.stockQuantity}")
        println("소요 시간: ${elapsed}ms")
        println("==========================================")

        assertEquals(100, successCount.get())
        assertEquals(0, failCount.get())
        assertEquals(0, updatedProduct.stockQuantity)
    }

    @Test
    @DisplayName("비관적 락: 재고 100개에 150명이 동시 구매하면 100명만 성공한다")
    fun pessimisticLock_150ConcurrentRequests_only100Succeed() {
        val threadCount = 150
        val executorService = Executors.newFixedThreadPool(32)
        val latch = CountDownLatch(threadCount)
        val successCount = AtomicInteger(0)
        val failCount = AtomicInteger(0)

        val startTime = System.currentTimeMillis()

        for (i in 1..threadCount) {
            executorService.submit {
                try {
                    pessimisticLockStockService.decreaseStock(product.id!!, 1)
                    successCount.incrementAndGet()
                } catch (e: Exception) {
                    failCount.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await()
        executorService.shutdown()

        val elapsed = System.currentTimeMillis() - startTime

        val updatedProduct = productJpaRepository.findById(product.id!!).get()

        println("=== 비관적 락 (FOR UPDATE) 초과 요청 테스트 결과 ===")
        println("동시 요청 수: $threadCount")
        println("성공: ${successCount.get()}")
        println("실패 (품절): ${failCount.get()}")
        println("최종 재고: ${updatedProduct.stockQuantity}")
        println("소요 시간: ${elapsed}ms")
        println("==========================================")

        assertEquals(100, successCount.get())
        assertEquals(50, failCount.get())
        assertEquals(0, updatedProduct.stockQuantity)
    }
}
