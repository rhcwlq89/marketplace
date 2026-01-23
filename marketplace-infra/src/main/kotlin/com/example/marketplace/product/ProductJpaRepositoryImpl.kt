package com.example.marketplace.product

import com.querydsl.core.types.dsl.BooleanExpression
import com.querydsl.jpa.impl.JPAQueryFactory
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.support.PageableExecutionUtils
import java.math.BigDecimal
import java.util.*

class ProductJpaRepositoryImpl(
    private val queryFactory: JPAQueryFactory
) : ProductJpaRepositoryCustom {

    private val product = QProduct.product

    override fun findByIdWithLock(id: Long): Optional<Product> {
        val result = queryFactory
            .selectFrom(product)
            .where(product.id.eq(id))
            .setLockMode(LockModeType.PESSIMISTIC_WRITE)
            .fetchOne()

        return Optional.ofNullable(result)
    }

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
