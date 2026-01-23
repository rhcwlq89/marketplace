package com.example.marketplace.category

import com.example.marketplace.common.CommonResponse
import com.example.marketplace.category.dto.CategoryResponse
import com.example.marketplace.category.dto.CreateCategoryRequest
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*

@Tag(name = "Categories", description = "카테고리 API")
@RestController
@RequestMapping("/api/v1")
class CategoryController(private val categoryService: CategoryService) {

    @Operation(summary = "카테고리 목록 조회")
    @GetMapping("/categories")
    fun getCategories(): CommonResponse<List<CategoryResponse>> {
        return CommonResponse.success(categoryService.getAllCategories())
    }

    @Operation(summary = "카테고리 등록 (관리자)")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/admin/categories")
    fun createCategory(
        @Valid @RequestBody req: CreateCategoryRequest
    ): CommonResponse<CategoryResponse> {
        return CommonResponse.success(categoryService.createCategory(req))
    }
}
