package com.example.marketplace.security

import com.nimbusds.jose.jwk.OctetSequenceKey
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.proc.SecurityContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.convert.converter.Converter
import org.springframework.http.HttpMethod
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.security.web.SecurityFilterChain
import javax.crypto.spec.SecretKeySpec

@Configuration
@EnableMethodSecurity
class SecurityConfig(private val jwtProperties: JwtProperties) {

    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    @Bean
    fun jwtDecoder(): JwtDecoder = NimbusJwtDecoder
        .withSecretKey(secretKey())
        .macAlgorithm(MacAlgorithm.HS256)
        .build()

    @Bean
    fun jwtEncoder(): JwtEncoder {
        val jwk = OctetSequenceKey.Builder(secretKey()).build()
        val jwkSource: JWKSource<SecurityContext> = ImmutableJWKSet(com.nimbusds.jose.jwk.JWKSet(jwk))
        return NimbusJwtEncoder(jwkSource)
    }

    @Bean
    fun jwtAuthenticationConverter(): Converter<Jwt, AbstractAuthenticationToken> =
        Converter { jwt ->
            val userId = jwt.subject.toLong()
            val role = jwt.getClaimAsString("role")
            val authorities = listOf(SimpleGrantedAuthority("ROLE_$role"))
            UsernamePasswordAuthenticationToken(userId, jwt.tokenValue, authorities)
        }

    @Bean
    fun filterChain(http: HttpSecurity, jwtAuthenticationConverter: Converter<Jwt, AbstractAuthenticationToken>): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { authz ->
                authz
                    .requestMatchers("/api/v1/auth/**").permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v1/products/**").permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v1/categories/**").permitAll()
                    .requestMatchers(
                        "/swagger-ui/**",
                        "/swagger-ui.html",
                        "/v3/api-docs/**",
                        "/swagger-resources/**",
                        "/webjars/**",
                    ).permitAll()
                    .requestMatchers("/h2-console/**").permitAll()
                    .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                    .anyRequest().authenticated()
            }
            .oauth2ResourceServer { oauth2 ->
                oauth2.jwt { it.jwtAuthenticationConverter(jwtAuthenticationConverter) }
            }
            .headers { it.frameOptions { fo -> fo.disable() } }

        return http.build()
    }

    private fun secretKey(): SecretKeySpec {
        val bytes = jwtProperties.secret.toByteArray()
        require(bytes.size >= 32) { "jwt.secret must be at least 32 bytes for HS256" }
        return SecretKeySpec(bytes, "HmacSHA256")
    }
}
