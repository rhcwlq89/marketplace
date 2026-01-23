package com.example.marketplace.product

import com.example.marketplace.category.Category
import com.example.marketplace.category.CategoryJpaRepository
import com.example.marketplace.common.BusinessException
import com.example.marketplace.common.ErrorCode
import com.example.marketplace.member.Member
import com.example.marketplace.member.MemberJpaRepository
import com.example.marketplace.member.Role
import com.example.marketplace.product.dto.CreateProductRequest
import com.example.marketplace.product.dto.UpdateProductRequest
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.util.*

class ProductServiceTest {

    private lateinit var productService: ProductService
    private lateinit var productJpaRepository: ProductJpaRepository
    private lateinit var memberJpaRepository: MemberJpaRepository
    private lateinit var categoryJpaRepository: CategoryJpaRepository

    private val seller = Member(
        id = 1L,
        email = "seller@example.com",
        password = "password",
        role = Role.SELLER,
        businessNumber = "123-45-67890"
    )

    @BeforeEach
    fun setup() {
        productJpaRepository = mockk()
        memberJpaRepository = mockk()
        categoryJpaRepository = mockk()
        productService = ProductService(productJpaRepository, memberJpaRepository, categoryJpaRepository)
    }

    @Test
    fun `createProduct should create product successfully`() {
        val request = CreateProductRequest(
            name = "Test Product",
            description = "Test Description",
            price = BigDecimal("10000"),
            stockQuantity = 100,
            status = "DRAFT"
        )

        every { memberJpaRepository.findById(seller.id!!) } returns Optional.of(seller)
        every { productJpaRepository.save(any()) } answers {
            val product = firstArg<Product>()
            product.id = 1L
            product
        }

        val response = productService.createProduct(seller.id!!, request)

        assertEquals("Test Product", response.name)
        assertEquals(BigDecimal("10000"), response.price)
        assertEquals("DRAFT", response.status)
    }

    @Test
    fun `updateProduct should update product when owned by seller`() {
        val product = Product(
            id = 1L,
            seller = seller,
            name = "Original Name",
            price = BigDecimal("10000"),
            stockQuantity = 100,
            status = ProductStatus.DRAFT
        )
        val request = UpdateProductRequest(
            name = "Updated Name",
            price = BigDecimal("20000")
        )

        every { productJpaRepository.findById(product.id!!) } returns Optional.of(product)
        every { productJpaRepository.save(any()) } answers { firstArg() }

        val response = productService.updateProduct(seller.id!!, product.id!!, request)

        assertEquals("Updated Name", response.name)
        assertEquals(BigDecimal("20000"), response.price)
    }

    @Test
    fun `updateProduct should throw exception when not owned by seller`() {
        val otherSeller = Member(
            id = 2L,
            email = "other@example.com",
            password = "password",
            role = Role.SELLER
        )
        val product = Product(
            id = 1L,
            seller = otherSeller,
            name = "Product",
            price = BigDecimal("10000"),
            stockQuantity = 100
        )

        every { productJpaRepository.findById(product.id!!) } returns Optional.of(product)

        val exception = assertThrows<BusinessException> {
            productService.updateProduct(seller.id!!, product.id!!, UpdateProductRequest(name = "New Name"))
        }

        assertEquals(ErrorCode.PRODUCT_NOT_OWNED, exception.errorCode)
    }

    @Test
    fun `deleteProduct should soft delete product`() {
        val product = Product(
            id = 1L,
            seller = seller,
            name = "Product",
            price = BigDecimal("10000"),
            stockQuantity = 100,
            status = ProductStatus.ON_SALE
        )

        every { productJpaRepository.findById(product.id!!) } returns Optional.of(product)
        every { productJpaRepository.save(any()) } answers { firstArg() }

        productService.deleteProduct(seller.id!!, product.id!!)

        verify { productJpaRepository.save(match { it.status == ProductStatus.DELETED }) }
    }

    @Test
    fun `getProduct should return product`() {
        val product = Product(
            id = 1L,
            seller = seller,
            name = "Product",
            price = BigDecimal("10000"),
            stockQuantity = 100
        )

        every { productJpaRepository.findById(product.id!!) } returns Optional.of(product)

        val response = productService.getProduct(product.id!!)

        assertEquals(product.id, response.id)
        assertEquals(product.name, response.name)
    }

    @Test
    fun `getProduct should throw exception when not found`() {
        every { productJpaRepository.findById(999L) } returns Optional.empty()

        val exception = assertThrows<BusinessException> {
            productService.getProduct(999L)
        }

        assertEquals(ErrorCode.PRODUCT_NOT_FOUND, exception.errorCode)
    }
}
