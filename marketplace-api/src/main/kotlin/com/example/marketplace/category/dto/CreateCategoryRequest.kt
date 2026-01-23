package com.example.marketplace.category.dto

import jakarta.validation.constraints.NotBlank

data class CreateCategoryRequest(
    @field:NotBlank
    val name: String,

    val parentId: Long? = null,

    val displayOrder: Int = 0
)
