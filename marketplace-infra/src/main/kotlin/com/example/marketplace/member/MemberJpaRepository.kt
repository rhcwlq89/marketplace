package com.example.marketplace.member

import org.springframework.data.jpa.repository.JpaRepository
import java.util.*

interface MemberJpaRepository : JpaRepository<Member, Long> {
    fun findByEmail(email: String): Optional<Member>
    fun existsByEmail(email: String): Boolean
}
