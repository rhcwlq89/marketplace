package com.example.marketplace.member

import com.example.marketplace.common.BaseEntity
import jakarta.persistence.*

@Entity
@Table(name = "members", indexes = [Index(columnList = "email", unique = true)])
class Member(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(nullable = false, unique = true)
    var email: String,

    @Column(nullable = false)
    var password: String,

    var name: String? = null,

    var phone: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var role: Role = Role.BUYER,

    var businessNumber: String? = null
) : BaseEntity() {
    fun update(name: String?, phone: String?) {
        name?.let { this.name = it }
        phone?.let { this.phone = it }
    }
}
