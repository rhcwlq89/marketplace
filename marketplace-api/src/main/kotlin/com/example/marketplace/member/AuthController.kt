package com.example.marketplace.member

import com.example.marketplace.common.CommonResponse
import com.example.marketplace.member.dto.LoginRequest
import com.example.marketplace.member.dto.LogoutRequest
import com.example.marketplace.member.dto.RefreshRequest
import com.example.marketplace.member.dto.SignupRequest
import com.example.marketplace.member.dto.TokenResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.*

@Tag(name = "Auth", description = "인증 API")
@RestController
@RequestMapping("/api/v1/auth")
class AuthController(private val authService: AuthService) {

    @Operation(summary = "회원가입")
    @PostMapping("/signup")
    fun signup(@Valid @RequestBody req: SignupRequest): CommonResponse<Unit> {
        authService.signup(req)
        return CommonResponse.success()
    }

    @Operation(summary = "로그인")
    @PostMapping("/login")
    fun login(@Valid @RequestBody req: LoginRequest): CommonResponse<TokenResponse> {
        return CommonResponse.success(authService.login(req))
    }

    @Operation(summary = "토큰 갱신")
    @PostMapping("/refresh")
    fun refresh(@Valid @RequestBody req: RefreshRequest): CommonResponse<TokenResponse> {
        return CommonResponse.success(authService.refresh(req))
    }

    @Operation(summary = "로그아웃")
    @PostMapping("/logout")
    fun logout(@Valid @RequestBody req: LogoutRequest): CommonResponse<Unit> {
        authService.logout(req.accessToken, req.refreshToken)
        return CommonResponse.success()
    }
}
