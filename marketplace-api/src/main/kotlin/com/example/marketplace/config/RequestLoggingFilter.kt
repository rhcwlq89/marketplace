package com.example.marketplace.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.*

@Component
class RequestLoggingFilter : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val requestId = UUID.randomUUID().toString().substring(0, 8)
        MDC.put("requestId", requestId)

        val startTime = System.currentTimeMillis()
        log.info("[{}] {} {} started", requestId, request.method, request.requestURI)

        try {
            filterChain.doFilter(request, response)
        } finally {
            val duration = System.currentTimeMillis() - startTime
            log.info("[{}] {} {} completed in {}ms with status {}",
                requestId, request.method, request.requestURI, duration, response.status)
            MDC.clear()
        }
    }
}
