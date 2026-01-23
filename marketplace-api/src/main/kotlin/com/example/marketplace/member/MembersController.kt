package com.example.marketplace.member

import com.example.marketplace.common.CommonResponse
import com.example.marketplace.member.dto.MemberResponse
import com.example.marketplace.member.dto.UpdateMemberRequest
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@Tag(name = "Members", description = "회원 API")
@RestController
@RequestMapping("/api/v1")
class MembersController(private val memberService: MemberService) {

    @Operation(summary = "내 정보 조회")
    @GetMapping("/members/me")
    fun getMe(@AuthenticationPrincipal memberId: Long): CommonResponse<MemberResponse> {
        return CommonResponse.success(memberService.getMe(memberId))
    }

    @Operation(summary = "내 정보 수정")
    @PatchMapping("/members/me")
    fun updateMe(
        @AuthenticationPrincipal memberId: Long,
        @RequestBody req: UpdateMemberRequest
    ): CommonResponse<MemberResponse> {
        return CommonResponse.success(memberService.updateMe(memberId, req))
    }

    @Operation(summary = "회원 목록 조회 (관리자)")
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/admin/members")
    fun getAllMembers(): CommonResponse<List<MemberResponse>> {
        return CommonResponse.success(memberService.getAllMembers())
    }
}
