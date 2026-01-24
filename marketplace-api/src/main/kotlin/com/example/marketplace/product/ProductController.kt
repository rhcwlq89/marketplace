package com.example.marketplace.product

import com.example.marketplace.common.CommonResponse
import com.example.marketplace.common.CursorPageResponse
import com.example.marketplace.product.dto.*
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.web.PageableDefault
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import java.math.BigDecimal

@Tag(name = "Products", description = "상품 API")
@RestController
@RequestMapping("/api/v1/products")
class ProductController(private val productService: ProductService) {

    @Operation(summary = "상품 등록 (판매자)")
    @PreAuthorize("hasRole('SELLER')")
    @PostMapping
    fun createProduct(
        @AuthenticationPrincipal sellerId: Long,
        @Valid @RequestBody req: CreateProductRequest
    ): CommonResponse<ProductResponse> {
        return CommonResponse.success(productService.createProduct(sellerId, req))
    }

    @Operation(summary = "상품 목록 조회")
    @GetMapping
    fun searchProducts(
        @RequestParam(required = false) keyword: String?,
        @RequestParam(required = false) categoryId: Long?,
        @RequestParam(required = false) minPrice: BigDecimal?,
        @RequestParam(required = false) maxPrice: BigDecimal?,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) sellerId: Long?,
        @PageableDefault(size = 20, sort = ["createdAt"], direction = Sort.Direction.DESC) pageable: Pageable
    ): CommonResponse<Page<ProductResponse>> {
        val req = ProductSearchRequest(keyword, categoryId, minPrice, maxPrice, status, sellerId)
        return CommonResponse.success(productService.searchProducts(req, pageable))
    }

    @Operation(summary = "상품 상세 조회")
    @GetMapping("/{productId}")
    fun getProduct(@PathVariable productId: Long): CommonResponse<ProductResponse> {
        return CommonResponse.success(productService.getProduct(productId))
    }

    @Operation(summary = "상품 수정 (판매자)")
    @PreAuthorize("hasRole('SELLER')")
    @PatchMapping("/{productId}")
    fun updateProduct(
        @AuthenticationPrincipal sellerId: Long,
        @PathVariable productId: Long,
        @RequestBody req: UpdateProductRequest
    ): CommonResponse<ProductResponse> {
        return CommonResponse.success(productService.updateProduct(sellerId, productId, req))
    }

    @Operation(summary = "상품 삭제 (판매자)")
    @PreAuthorize("hasRole('SELLER')")
    @DeleteMapping("/{productId}")
    fun deleteProduct(
        @AuthenticationPrincipal sellerId: Long,
        @PathVariable productId: Long
    ): CommonResponse<Unit> {
        productService.deleteProduct(sellerId, productId)
        return CommonResponse.success()
    }

    @Operation(summary = "상품 이미지 업로드 (판매자)")
    @PreAuthorize("hasRole('SELLER')")
    @PostMapping("/{productId}/images", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun uploadImages(
        @AuthenticationPrincipal sellerId: Long,
        @PathVariable productId: Long,
        @RequestParam("files") files: List<MultipartFile>
    ): CommonResponse<ProductResponse> {
        return CommonResponse.success(productService.uploadImages(sellerId, productId, files))
    }

    @Operation(summary = "인기 상품 목록")
    @GetMapping("/popular")
    fun getPopularProducts(): CommonResponse<List<ProductResponse>> {
        return CommonResponse.success(productService.getPopularProducts())
    }

    @Operation(summary = "상품 목록 조회 (커서 기반 페이지네이션)")
    @GetMapping("/cursor")
    fun searchProductsWithCursor(
        @RequestParam(required = false) keyword: String?,
        @RequestParam(required = false) categoryId: Long?,
        @RequestParam(required = false) minPrice: BigDecimal?,
        @RequestParam(required = false) maxPrice: BigDecimal?,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) sellerId: Long?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "20") limit: Int
    ): CommonResponse<CursorPageResponse<ProductResponse>> {
        val req = ProductSearchRequest(keyword, categoryId, minPrice, maxPrice, status, sellerId)
        return CommonResponse.success(productService.searchProductsWithCursor(req, cursor, limit))
    }
}
