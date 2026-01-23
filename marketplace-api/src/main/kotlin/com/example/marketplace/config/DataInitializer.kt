package com.example.marketplace.config

import com.example.marketplace.category.Category
import com.example.marketplace.category.CategoryJpaRepository
import com.example.marketplace.member.Member
import com.example.marketplace.member.MemberJpaRepository
import com.example.marketplace.member.Role
import com.example.marketplace.product.Product
import com.example.marketplace.product.ProductJpaRepository
import com.example.marketplace.product.ProductStatus
import org.slf4j.LoggerFactory
import org.springframework.boot.CommandLineRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.security.crypto.password.PasswordEncoder
import java.math.BigDecimal

@Configuration
@Profile("local", "docker")
class DataInitializer {

    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun initData(
        memberJpaRepository: MemberJpaRepository,
        categoryJpaRepository: CategoryJpaRepository,
        productJpaRepository: ProductJpaRepository,
        passwordEncoder: PasswordEncoder
    ) = CommandLineRunner {

        // Check if data already exists
        if (memberJpaRepository.findByEmail("admin@example.com").isPresent) {
            log.info("Seed data already exists. Skipping initialization.")
            return@CommandLineRunner
        }

        log.info("Initializing seed data...")

        // Create test members
        val admin = memberJpaRepository.save(
            Member(
                email = "admin@example.com",
                password = passwordEncoder.encode("admin123!"),
                name = "Admin User",
                phone = "010-0000-0000",
                role = Role.ADMIN
            )
        )
        log.info("Created admin: {}", admin.email)

        val seller = memberJpaRepository.save(
            Member(
                email = "seller@example.com",
                password = passwordEncoder.encode("seller123!"),
                name = "Seller User",
                phone = "010-1111-1111",
                role = Role.SELLER,
                businessNumber = "123-45-67890"
            )
        )
        log.info("Created seller: {}", seller.email)

        val buyer = memberJpaRepository.save(
            Member(
                email = "buyer@example.com",
                password = passwordEncoder.encode("buyer123!"),
                name = "Buyer User",
                phone = "010-2222-2222",
                role = Role.BUYER
            )
        )
        log.info("Created buyer: {}", buyer.email)

        // Create categories
        val electronics = categoryJpaRepository.save(
            Category(name = "Electronics", displayOrder = 1)
        )
        val clothing = categoryJpaRepository.save(
            Category(name = "Clothing", displayOrder = 2)
        )
        val books = categoryJpaRepository.save(
            Category(name = "Books", displayOrder = 3)
        )
        log.info("Created categories: Electronics, Clothing, Books")

        // Create sample products
        productJpaRepository.save(
            Product(
                seller = seller,
                category = electronics,
                name = "Laptop",
                description = "High-performance laptop for professionals",
                price = BigDecimal("1500000"),
                stockQuantity = 50,
                status = ProductStatus.ON_SALE
            )
        )

        productJpaRepository.save(
            Product(
                seller = seller,
                category = electronics,
                name = "Smartphone",
                description = "Latest smartphone with amazing camera",
                price = BigDecimal("900000"),
                stockQuantity = 100,
                status = ProductStatus.ON_SALE
            )
        )

        productJpaRepository.save(
            Product(
                seller = seller,
                category = clothing,
                name = "Winter Jacket",
                description = "Warm and stylish winter jacket",
                price = BigDecimal("150000"),
                stockQuantity = 30,
                status = ProductStatus.ON_SALE
            )
        )

        productJpaRepository.save(
            Product(
                seller = seller,
                category = books,
                name = "Kotlin Programming",
                description = "Comprehensive guide to Kotlin programming",
                price = BigDecimal("35000"),
                stockQuantity = 200,
                status = ProductStatus.ON_SALE
            )
        )

        log.info("Created sample products")
        log.info("Seed data initialization completed!")
        log.info("")
        log.info("=== Test Accounts ===")
        log.info("ADMIN: admin@example.com / admin123!")
        log.info("SELLER: seller@example.com / seller123!")
        log.info("BUYER: buyer@example.com / buyer123!")
        log.info("=====================")
    }
}
