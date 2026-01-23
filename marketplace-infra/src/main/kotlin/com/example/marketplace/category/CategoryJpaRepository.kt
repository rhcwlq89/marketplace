package com.example.marketplace.category

import org.springframework.data.jpa.repository.JpaRepository

interface CategoryJpaRepository : JpaRepository<Category, Long> {
    fun findByParentIsNull(): List<Category>
}
