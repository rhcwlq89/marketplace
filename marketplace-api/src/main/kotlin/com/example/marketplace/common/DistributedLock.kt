package com.example.marketplace.common

import com.example.marketplace.common.BusinessException
import com.example.marketplace.common.ErrorCode
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.reflect.MethodSignature
import org.redisson.api.RedissonClient
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.core.annotation.Order
import org.springframework.expression.ExpressionParser
import org.springframework.expression.spel.standard.SpelExpressionParser
import org.springframework.expression.spel.support.StandardEvaluationContext
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class DistributedLock(
    val key: String,
    val waitTime: Long = 5,
    val leaseTime: Long = 10,
    val timeUnit: TimeUnit = TimeUnit.SECONDS
)

@Aspect
@Component
@Order(1)
@Profile("docker", "prod")
class DistributedLockAspect(
    private val redissonClient: RedissonClient
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val parser: ExpressionParser = SpelExpressionParser()

    @Around("@annotation(distributedLock)")
    fun around(joinPoint: ProceedingJoinPoint, distributedLock: DistributedLock): Any? {
        val lockKey = parseKey(joinPoint, distributedLock.key)
        val lock = redissonClient.getLock(lockKey)

        val acquired = try {
            lock.tryLock(distributedLock.waitTime, distributedLock.leaseTime, distributedLock.timeUnit)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw BusinessException(ErrorCode.LOCK_ACQUISITION_FAILED)
        }

        if (!acquired) {
            log.warn("Failed to acquire lock for key: $lockKey")
            throw BusinessException(ErrorCode.LOCK_ACQUISITION_FAILED)
        }

        return try {
            log.debug("Acquired lock for key: $lockKey")
            joinPoint.proceed()
        } finally {
            try {
                if (lock.isHeldByCurrentThread) {
                    lock.unlock()
                    log.debug("Released lock for key: $lockKey")
                }
            } catch (e: Exception) {
                log.warn("Failed to release lock for key: $lockKey", e)
            }
        }
    }

    private fun parseKey(joinPoint: ProceedingJoinPoint, keyExpression: String): String {
        val signature = joinPoint.signature as MethodSignature
        val parameterNames = signature.parameterNames
        val args = joinPoint.args

        val context = StandardEvaluationContext()
        parameterNames.forEachIndexed { index, name ->
            context.setVariable(name, args[index])
        }

        return try {
            parser.parseExpression(keyExpression).getValue(context, String::class.java)
                ?: "lock:default"
        } catch (e: Exception) {
            log.warn("Failed to parse lock key expression: $keyExpression, using default")
            "lock:$keyExpression"
        }
    }
}

@Component
@Profile("local")
class NoOpDistributedLockAspect {
    private val log = LoggerFactory.getLogger(javaClass)

    @org.aspectj.lang.annotation.Around("@annotation(distributedLock)")
    fun around(joinPoint: ProceedingJoinPoint, distributedLock: DistributedLock): Any? {
        log.debug("NoOp distributed lock for local profile")
        return joinPoint.proceed()
    }
}
