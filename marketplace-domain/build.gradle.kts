plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.jpa")
    id("org.jetbrains.kotlin.kapt")
}

val querydslVersion = "5.0.0"

dependencies {
    implementation(project(":marketplace-common"))
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // QueryDSL
    implementation("com.querydsl:querydsl-jpa:$querydslVersion:jakarta")
    kapt("com.querydsl:querydsl-apt:$querydslVersion:jakarta")
}

tasks.jar {
    enabled = true
}
