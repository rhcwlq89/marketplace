import org.asciidoctor.gradle.jvm.AsciidoctorTask

plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    id("org.asciidoctor.jvm.convert") version "4.0.4"
}

dependencies {
    implementation(project(":marketplace-common"))
    implementation(project(":marketplace-domain"))
    implementation(project(":marketplace-infra"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // AOP — spring-boot-starter-aop was removed in Spring Boot 4; pull AspectJ directly
    implementation("org.aspectj:aspectjweaver")

    // Cache
    implementation("org.springframework.boot:spring-boot-starter-cache")
    implementation("com.github.ben-manes.caffeine:caffeine:3.1.8")

    // JWT — Spring Security 7 oauth2-resource-server (NimbusJwtEncoder / NimbusJwtDecoder)
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")

    // Swagger
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.3")

    // Database
    runtimeOnly("com.h2database:h2")
    runtimeOnly("com.mysql:mysql-connector-j")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.springframework.restdocs:spring-restdocs-mockmvc")
}

val snippetsDir = layout.buildDirectory.dir("generated-snippets")

tasks.test {
    outputs.dir(snippetsDir)
}

tasks.named<AsciidoctorTask>("asciidoctor") {
    inputs.dir(snippetsDir)
    dependsOn(tasks.test)
    attributes(
        mapOf(
            "snippets" to snippetsDir.get().asFile.absolutePath,
            "source-highlighter" to "highlightjs",
            "toc" to "left",
            "toclevels" to 3,
            "sectlinks" to true,
        )
    )
}
