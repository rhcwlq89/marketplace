package com.example.marketplace.category

import com.example.marketplace.category.dto.CategoryResponse
import com.example.marketplace.category.dto.CreateCategoryRequest
import com.example.marketplace.common.BusinessException
import com.example.marketplace.common.ErrorCode
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class CategoryService(
    private val categoryJpaRepository: CategoryJpaRepository
) {

    @Cacheable(value = ["categories"], key = "'all'")
    fun getAllCategories(): List<CategoryResponse> {
        return categoryJpaRepository.findAll()
            .sortedBy { it.displayOrder }
            .map { CategoryResponse.from(it) }
    }

    @Transactional
    @CacheEvict(value = ["categories"], allEntries = true)
    fun createCategory(req: CreateCategoryRequest): CategoryResponse {
        val parent = req.parentId?.let {
            categoryJpaRepository.findById(it)
                .orElseThrow { BusinessException(ErrorCode.CATEGORY_NOT_FOUND) }
        }

        val category = Category(
            name = req.name,
            parent = parent,
            displayOrder = req.displayOrder
        )

        return CategoryResponse.from(categoryJpaRepository.save(category))
    }
}
