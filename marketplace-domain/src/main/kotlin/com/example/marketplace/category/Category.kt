package com.example.marketplace.category

import com.example.marketplace.common.BaseEntity
import jakarta.persistence.*

@Entity
@Table(name = "categories")
class Category(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(nullable = false)
    var name: String,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    var parent: Category? = null,

    var displayOrder: Int = 0
) : BaseEntity()
