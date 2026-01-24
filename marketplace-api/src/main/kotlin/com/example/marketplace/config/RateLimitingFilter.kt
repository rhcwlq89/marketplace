package com.example.marketplace.config

import com.example.marketplace.common.ErrorCode
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.resilience4j.ratelimiter.RateLimiterRegistry
import io.github.resilience4j.ratelimiter.RequestNotPermitted
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
class RateLimitingFilter(
    private val rateLimiterRegistry: RateLimiterRegistry,
    private val objectMapper: ObjectMapper
) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val rateLimiterName = determineRateLimiter(request)
        val rateLimiter = rateLimiterRegistry.rateLimiter(rateLimiterName)

        try {
            val permission = rateLimiter.acquirePermission()
            if (permission) {
                filterChain.doFilter(request, response)
            } else {
                handleRateLimitExceeded(response)
            }
        } catch (e: RequestNotPermitted) {
            log.warn("Rate limit exceeded for ${request.requestURI} from ${request.remoteAddr}")
            handleRateLimitExceeded(response)
        }
    }

    private fun determineRateLimiter(request: HttpServletRequest): String {
        return when {
            request.requestURI.startsWith("/api/v1/orders") && request.method == "POST" -> "orderCreation"
            else -> "default"
        }
    }

    private fun handleRateLimitExceeded(response: HttpServletResponse) {
        response.status = ErrorCode.RATE_LIMITED.status.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE

        val errorResponse = mapOf(
            "success" to false,
            "code" to ErrorCode.RATE_LIMITED.name,
            "message" to ErrorCode.RATE_LIMITED.message
        )

        response.writer.write(objectMapper.writeValueAsString(errorResponse))
    }

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val path = request.requestURI
        return path.startsWith("/actuator") ||
                path.startsWith("/swagger") ||
                path.startsWith("/v3/api-docs") ||
                path.startsWith("/h2-console")
    }
}
