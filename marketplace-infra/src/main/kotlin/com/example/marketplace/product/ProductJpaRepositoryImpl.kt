package com.example.marketplace.product

import com.querydsl.core.types.dsl.BooleanExpression
import com.querydsl.jpa.impl.JPAQueryFactory
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import jakarta.persistence.PersistenceContext
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.support.PageableExecutionUtils
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.*

class ProductJpaRepositoryImpl(
    private val queryFactory: JPAQueryFactory
) : ProductJpaRepositoryCustom {

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    private val product = QProduct.product

    @Transactional
    override fun findByIdWithLock(id: Long): Optional<Product> {
        val result = queryFactory
            .selectFrom(product)
            .where(product.id.eq(id))
            .setLockMode(LockModeType.PESSIMISTIC_WRITE)
            .fetchOne()

        return Optional.ofNullable(result)
    }

    @Transactional(readOnly = true)
    override fun search(
        keyword: String?,
        categoryId: Long?,
        minPrice: BigDecimal?,
        maxPrice: BigDecimal?,
        status: ProductStatus?,
        sellerId: Long?,
        pageable: Pageable
    ): Page<Product> {
        val content = queryFactory
            .selectFrom(product)
            .where(
                keywordContains(keyword),
                categoryIdEq(categoryId),
                priceGoe(minPrice),
                priceLoe(maxPrice),
                statusEq(status),
                sellerIdEq(sellerId),
                notDeleted()
            )
            .offset(pageable.offset)
            .limit(pageable.pageSize.toLong())
            .orderBy(product.createdAt.desc())
            .fetch()

        val countQuery = queryFactory
            .select(product.count())
            .from(product)
            .where(
                keywordContains(keyword),
                categoryIdEq(categoryId),
                priceGoe(minPrice),
                priceLoe(maxPrice),
                statusEq(status),
                sellerIdEq(sellerId),
                notDeleted()
            )

        return PageableExecutionUtils.getPage(content, pageable) {
            countQuery.fetchOne() ?: 0L
        }
    }

    @Transactional
    override fun decreaseStockAtomically(productId: Long, quantity: Int): Int {
        val updateCount = entityManager.createQuery(
            """
            UPDATE Product p
            SET p.stockQuantity = p.stockQuantity - :quantity,
                p.salesCount = p.salesCount + :quantity,
                p.updatedAt = :now
            WHERE p.id = :productId
            AND p.stockQuantity >= :quantity
            AND p.status = :status
            """.trimIndent()
        )
            .setParameter("quantity", quantity)
            .setParameter("productId", productId)
            .setParameter("now", LocalDateTime.now())
            .setParameter("status", ProductStatus.ON_SALE)
            .executeUpdate()

        return updateCount
    }

    @Transactional
    override fun restoreStockAtomically(productId: Long, quantity: Int): Int {
        val updateCount = entityManager.createQuery(
            """
            UPDATE Product p
            SET p.stockQuantity = p.stockQuantity + :quantity,
                p.salesCount = CASE WHEN p.salesCount >= :quantity THEN p.salesCount - :quantity ELSE 0 END,
                p.updatedAt = :now
            WHERE p.id = :productId
            """.trimIndent()
        )
            .setParameter("quantity", quantity)
            .setParameter("productId", productId)
            .setParameter("now", LocalDateTime.now())
            .executeUpdate()

        return updateCount
    }

    @Transactional(readOnly = true)
    override fun searchWithCursor(
        keyword: String?,
        categoryId: Long?,
        minPrice: BigDecimal?,
        maxPrice: BigDecimal?,
        status: ProductStatus?,
        sellerId: Long?,
        cursor: LocalDateTime?,
        cursorId: Long?,
        limit: Int
    ): List<Product> {
        return queryFactory
            .selectFrom(product)
            .where(
                keywordContains(keyword),
                categoryIdEq(categoryId),
                priceGoe(minPrice),
                priceLoe(maxPrice),
                statusEq(status),
                sellerIdEq(sellerId),
                notDeleted(),
                cursorCondition(cursor, cursorId)
            )
            .orderBy(product.createdAt.desc(), product.id.desc())
            .limit(limit.toLong())
            .fetch()
    }

    private fun cursorCondition(cursor: LocalDateTime?, cursorId: Long?): BooleanExpression? {
        if (cursor == null || cursorId == null) {
            return null
        }
        return product.createdAt.lt(cursor)
            .or(product.createdAt.eq(cursor).and(product.id.lt(cursorId)))
    }

    private fun keywordContains(keyword: String?): BooleanExpression? {
        return keyword?.takeIf { it.isNotBlank() }?.let {
            product.name.containsIgnoreCase(it)
                .or(product.description.containsIgnoreCase(it))
        }
    }

    private fun categoryIdEq(categoryId: Long?): BooleanExpression? {
        return categoryId?.let { product.category.id.eq(it) }
    }

    private fun priceGoe(minPrice: BigDecimal?): BooleanExpression? {
        return minPrice?.let { product.price.goe(it) }
    }

    private fun priceLoe(maxPrice: BigDecimal?): BooleanExpression? {
        return maxPrice?.let { product.price.loe(it) }
    }

    private fun statusEq(status: ProductStatus?): BooleanExpression? {
        return status?.let { product.status.eq(it) }
    }

    private fun sellerIdEq(sellerId: Long?): BooleanExpression? {
        return sellerId?.let { product.seller.id.eq(it) }
    }

    private fun notDeleted(): BooleanExpression {
        return product.status.ne(ProductStatus.DELETED)
    }
}
