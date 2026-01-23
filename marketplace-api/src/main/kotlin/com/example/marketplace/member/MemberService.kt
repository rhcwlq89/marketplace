package com.example.marketplace.member

import com.example.marketplace.common.BusinessException
import com.example.marketplace.common.ErrorCode
import com.example.marketplace.member.dto.MemberResponse
import com.example.marketplace.member.dto.UpdateMemberRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class MemberService(
    private val memberJpaRepository: MemberJpaRepository
) {

    fun getMe(memberId: Long): MemberResponse {
        val member = memberJpaRepository.findById(memberId)
            .orElseThrow { BusinessException(ErrorCode.MEMBER_NOT_FOUND) }
        return MemberResponse.from(member)
    }

    @Transactional
    fun updateMe(memberId: Long, req: UpdateMemberRequest): MemberResponse {
        val member = memberJpaRepository.findById(memberId)
            .orElseThrow { BusinessException(ErrorCode.MEMBER_NOT_FOUND) }
        member.update(req.name, req.phone)
        return MemberResponse.from(memberJpaRepository.save(member))
    }

    fun getAllMembers(): List<MemberResponse> {
        return memberJpaRepository.findAll().map { MemberResponse.from(it) }
    }
}
