package com.example.marketplace.product

import com.example.marketplace.category.CategoryJpaRepository
import com.example.marketplace.common.BusinessException
import com.example.marketplace.common.CursorPageResponse
import com.example.marketplace.common.ErrorCode
import com.example.marketplace.member.MemberJpaRepository
import com.example.marketplace.product.dto.*
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import org.slf4j.LoggerFactory
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.Cacheable
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*

@Service
@Transactional(readOnly = true)
class ProductService(
    private val productJpaRepository: ProductJpaRepository,
    private val memberJpaRepository: MemberJpaRepository,
    private val categoryJpaRepository: CategoryJpaRepository
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val uploadDir: Path = Paths.get("uploads/products")
    private val allowedExtensions = listOf("jpg", "jpeg", "png", "gif")
    private val maxFileSize = 10 * 1024 * 1024L // 10MB

    init {
        Files.createDirectories(uploadDir)
    }

    @Transactional
    fun createProduct(sellerId: Long, req: CreateProductRequest): ProductResponse {
        val seller = memberJpaRepository.findById(sellerId)
            .orElseThrow { BusinessException(ErrorCode.MEMBER_NOT_FOUND) }

        val category = req.categoryId?.let {
            categoryJpaRepository.findById(it)
                .orElseThrow { BusinessException(ErrorCode.CATEGORY_NOT_FOUND) }
        }

        val product = Product(
            seller = seller,
            category = category,
            name = req.name,
            description = req.description,
            price = req.price,
            stockQuantity = req.stockQuantity,
            status = ProductStatus.valueOf(req.status)
        )

        return ProductResponse.from(productJpaRepository.save(product))
    }

    fun getProduct(productId: Long): ProductResponse {
        val product = productJpaRepository.findById(productId)
            .orElseThrow { BusinessException(ErrorCode.PRODUCT_NOT_FOUND) }
        return ProductResponse.from(product)
    }

    @CircuitBreaker(name = "productService", fallbackMethod = "searchProductsFallback")
    fun searchProducts(req: ProductSearchRequest, pageable: Pageable): Page<ProductResponse> {
        val status = req.status?.let { ProductStatus.valueOf(it) }
        return productJpaRepository.search(
            keyword = req.keyword,
            categoryId = req.categoryId,
            minPrice = req.minPrice,
            maxPrice = req.maxPrice,
            status = status,
            sellerId = req.sellerId,
            pageable = pageable
        ).map { ProductResponse.from(it) }
    }

    @Transactional
    @CacheEvict(value = ["popularProducts"], allEntries = true)
    fun updateProduct(sellerId: Long, productId: Long, req: UpdateProductRequest): ProductResponse {
        val product = productJpaRepository.findById(productId)
            .orElseThrow { BusinessException(ErrorCode.PRODUCT_NOT_FOUND) }

        if (product.seller.id != sellerId) {
            throw BusinessException(ErrorCode.PRODUCT_NOT_OWNED)
        }

        val category = req.categoryId?.let {
            categoryJpaRepository.findById(it)
                .orElseThrow { BusinessException(ErrorCode.CATEGORY_NOT_FOUND) }
        }

        val status = req.status?.let { ProductStatus.valueOf(it) }
        product.update(req.name, req.description, req.price, req.stockQuantity, status, category)

        return ProductResponse.from(productJpaRepository.save(product))
    }

    @Transactional
    @CacheEvict(value = ["popularProducts"], allEntries = true)
    fun deleteProduct(sellerId: Long, productId: Long) {
        val product = productJpaRepository.findById(productId)
            .orElseThrow { BusinessException(ErrorCode.PRODUCT_NOT_FOUND) }

        if (product.seller.id != sellerId) {
            throw BusinessException(ErrorCode.PRODUCT_NOT_OWNED)
        }

        product.status = ProductStatus.DELETED
        productJpaRepository.save(product)
    }

    @Transactional
    fun uploadImages(sellerId: Long, productId: Long, files: List<MultipartFile>): ProductResponse {
        val product = productJpaRepository.findById(productId)
            .orElseThrow { BusinessException(ErrorCode.PRODUCT_NOT_FOUND) }

        if (product.seller.id != sellerId) {
            throw BusinessException(ErrorCode.PRODUCT_NOT_OWNED)
        }

        if (product.images.size + files.size > 5) {
            throw BusinessException(ErrorCode.MAX_IMAGES_EXCEEDED)
        }

        val productUploadDir = uploadDir.resolve(productId.toString())
        Files.createDirectories(productUploadDir)

        files.forEachIndexed { index, file ->
            val extension = file.originalFilename?.substringAfterLast('.', "")?.lowercase()
            if (extension !in allowedExtensions) {
                throw BusinessException(ErrorCode.INVALID_FILE_TYPE)
            }
            if (file.size > maxFileSize) {
                throw BusinessException(ErrorCode.FILE_SIZE_EXCEEDED)
            }

            val filename = "${UUID.randomUUID()}.$extension"
            val filePath = productUploadDir.resolve(filename)
            file.transferTo(filePath.toFile())

            val image = ProductImage(
                imageUrl = "/uploads/products/$productId/$filename",
                displayOrder = product.images.size + index
            )
            product.addImage(image)
        }

        return ProductResponse.from(productJpaRepository.save(product))
    }

    @Cacheable(value = ["popularProducts"], key = "'top10'")
    fun getPopularProducts(): List<ProductResponse> {
        return productJpaRepository.findByStatusOrderBySalesCountDesc(ProductStatus.ON_SALE, PageRequest.of(0, 10))
            .map { ProductResponse.from(it) }
    }

    fun getMyProducts(sellerId: Long, pageable: Pageable): Page<ProductResponse> {
        return productJpaRepository.findBySellerId(sellerId, pageable)
            .map { ProductResponse.from(it) }
    }

    fun searchProductsWithCursor(
        req: ProductSearchRequest,
        cursor: String?,
        limit: Int
    ): CursorPageResponse<ProductResponse> {
        val (cursorTimestamp, cursorId) = cursor?.let { CursorPageResponse.decodeCursor(it) }
            ?: Pair(null, null)

        val status = req.status?.let { ProductStatus.valueOf(it) }
        val products = productJpaRepository.searchWithCursor(
            keyword = req.keyword,
            categoryId = req.categoryId,
            minPrice = req.minPrice,
            maxPrice = req.maxPrice,
            status = status,
            sellerId = req.sellerId,
            cursor = cursorTimestamp,
            cursorId = cursorId,
            limit = limit + 1
        )

        return CursorPageResponse.of(
            content = products.map { ProductResponse.from(it) },
            limit = limit
        ) { response ->
            val product = products.find { it.id == response.id }!!
            product.createdAt to product.id!!
        }
    }

    private fun searchProductsFallback(req: ProductSearchRequest, pageable: Pageable, ex: Throwable): Page<ProductResponse> {
        log.error("Circuit breaker fallback triggered for searchProducts. Error: ${ex.message}")
        throw BusinessException(ErrorCode.SERVICE_UNAVAILABLE)
    }
}
