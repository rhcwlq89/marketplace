package com.example.marketplace.category.dto

import com.example.marketplace.category.Category

data class CategoryResponse(
    val id: Long,
    val name: String,
    val parentId: Long?,
    val displayOrder: Int
) {
    companion object {
        fun from(category: Category) = CategoryResponse(
            id = category.id!!,
            name = category.name,
            parentId = category.parent?.id,
            displayOrder = category.displayOrder
        )
    }
}
