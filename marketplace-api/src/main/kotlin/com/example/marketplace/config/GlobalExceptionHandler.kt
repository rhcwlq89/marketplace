package com.example.marketplace.config

import com.example.marketplace.common.BusinessException
import com.example.marketplace.common.CommonResponse
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(BusinessException::class)
    fun handleBusinessException(
        ex: BusinessException,
        request: HttpServletRequest
    ): ResponseEntity<CommonResponse<Nothing>> {
        log.warn("[{}] BusinessException: {}", MDC.get("requestId"), ex.message)
        return ResponseEntity
            .status(ex.errorCode.status)
            .body(CommonResponse.error(ex.errorCode.name, ex.message))
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidationException(
        ex: MethodArgumentNotValidException,
        request: HttpServletRequest
    ): ResponseEntity<CommonResponse<Nothing>> {
        val message = ex.bindingResult.fieldErrors
            .joinToString(", ") { "${it.field}: ${it.defaultMessage}" }
        log.warn("[{}] Validation error: {}", MDC.get("requestId"), message)
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(CommonResponse.error("VALIDATION_ERROR", message))
    }

    @ExceptionHandler(AccessDeniedException::class)
    fun handleAccessDeniedException(
        ex: AccessDeniedException,
        request: HttpServletRequest
    ): ResponseEntity<CommonResponse<Nothing>> {
        log.warn("[{}] Access denied: {}", MDC.get("requestId"), ex.message)
        return ResponseEntity
            .status(HttpStatus.FORBIDDEN)
            .body(CommonResponse.error("ACCESS_DENIED", "Access denied"))
    }

    @ExceptionHandler(Exception::class)
    fun handleException(
        ex: Exception,
        request: HttpServletRequest
    ): ResponseEntity<CommonResponse<Nothing>> {
        log.error("[{}] Unhandled exception: {}", MDC.get("requestId"), ex.message, ex)
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(CommonResponse.error("INTERNAL_ERROR", "Internal server error"))
    }
}
