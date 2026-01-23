package com.example.marketplace.config

import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.annotation.Pointcut
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Aspect
@Component
class LoggingAspect {

    private val log = LoggerFactory.getLogger(javaClass)

    @Pointcut("within(com.example.marketplace..*Controller)")
    fun controllerPointcut() {}

    @Pointcut("within(com.example.marketplace..*Service)")
    fun servicePointcut() {}

    @Around("controllerPointcut() || servicePointcut()")
    fun logAround(joinPoint: ProceedingJoinPoint): Any? {
        val className = joinPoint.signature.declaringTypeName
        val methodName = joinPoint.signature.name

        log.debug("Enter: {}.{}() with arguments = {}", className, methodName, joinPoint.args)

        val startTime = System.currentTimeMillis()
        return try {
            val result = joinPoint.proceed()
            val duration = System.currentTimeMillis() - startTime
            log.debug("Exit: {}.{}() with result = {} ({}ms)", className, methodName, result, duration)
            result
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            log.error("Exception in {}.{}() after {}ms: {}", className, methodName, duration, e.message)
            throw e
        }
    }
}
