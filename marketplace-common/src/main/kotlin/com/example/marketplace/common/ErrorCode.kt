package com.example.marketplace.common

import org.springframework.http.HttpStatus

enum class ErrorCode(
    val status: HttpStatus,
    val message: String
) {
    // Common
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "Invalid input"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error"),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "Access denied"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "Unauthorized"),

    // Member
    EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "Email already exists"),
    MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "Member not found"),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Invalid credentials"),
    BUSINESS_NUMBER_REQUIRED(HttpStatus.BAD_REQUEST, "Business number is required for seller"),
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "Invalid token"),

    // Product
    PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "Product not found"),
    PRODUCT_NOT_OWNED(HttpStatus.FORBIDDEN, "You don't own this product"),
    INVALID_PRODUCT_STATUS(HttpStatus.BAD_REQUEST, "Invalid product status"),
    MAX_IMAGES_EXCEEDED(HttpStatus.BAD_REQUEST, "Maximum 5 images allowed"),
    INVALID_FILE_TYPE(HttpStatus.BAD_REQUEST, "Invalid file type"),
    FILE_SIZE_EXCEEDED(HttpStatus.BAD_REQUEST, "File size exceeded 10MB"),
    INSUFFICIENT_STOCK(HttpStatus.BAD_REQUEST, "Insufficient stock"),

    // Category
    CATEGORY_NOT_FOUND(HttpStatus.NOT_FOUND, "Category not found"),

    // Order
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "Order not found"),
    ORDER_NOT_OWNED(HttpStatus.FORBIDDEN, "You don't own this order"),
    INVALID_ORDER_STATUS(HttpStatus.BAD_REQUEST, "Invalid order status"),
    CANNOT_CANCEL_ORDER(HttpStatus.BAD_REQUEST, "Cannot cancel order in current status"),
    EMPTY_ORDER_ITEMS(HttpStatus.BAD_REQUEST, "Order items cannot be empty")
}
