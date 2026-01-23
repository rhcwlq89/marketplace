package com.example.marketplace.member.dto

import com.example.marketplace.member.Member
import java.time.LocalDateTime

data class MemberResponse(
    val id: Long,
    val email: String,
    val name: String?,
    val phone: String?,
    val role: String,
    val businessNumber: String?,
    val createdAt: LocalDateTime
) {
    companion object {
        fun from(member: Member) = MemberResponse(
            id = member.id!!,
            email = member.email,
            name = member.name,
            phone = member.phone,
            role = member.role.name,
            businessNumber = member.businessNumber,
            createdAt = member.createdAt
        )
    }
}
