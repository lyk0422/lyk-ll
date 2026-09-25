package com.example.starter.pension.service;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.pension.domain.EmploymentStatus;
import com.example.starter.pension.dto.MemberRequest;
import com.example.starter.pension.dto.MemberResponse;
import com.example.starter.pension.repo.MemberRepository;
import com.example.starter.pension.repo.MemberRepository.MemberPo;
import com.example.starter.pension.web.BadRequestException;
import com.example.starter.pension.web.ConflictException;
import com.example.starter.pension.web.NotFoundException;

/**
 * 成员登记服务（合成数据）。
 */
@Service
public class MemberService {

    private final MemberRepository memberRepository;

    public MemberService(MemberRepository memberRepository) {
        this.memberRepository = memberRepository;
    }

    /**
     * 登记成员。
     */
    @Transactional
    public MemberResponse register(MemberRequest request) {
        MemberPo po = new MemberPo(
                0L, request.memberNo(), request.name(), request.hireDate(), request.status(), null);
        try {
            memberRepository.insert(po);
        } catch (DuplicateKeyException e) {
            throw new ConflictException("成员编号已存在: " + request.memberNo());
        }
        return new MemberResponse(
                request.memberNo(), request.name(), request.hireDate(), request.status());
    }

    /**
     * 查询成员，不存在抛 404。
     */
    public MemberPo requireMember(String memberNo) {
        return memberRepository.findByNo(memberNo)
                .orElseThrow(() -> new NotFoundException("成员不存在: " + memberNo));
    }

    /**
     * 校验支付事由与成员在职状态一致：离职支付要求已离职、退休支付要求已退休。
     */
    public void requirePayableStatus(MemberPo member, com.example.starter.pension.domain.PayoutReason reason) {
        EmploymentStatus expected = switch (reason) {
            case RESIGNED -> EmploymentStatus.RESIGNED;
            case RETIRED -> EmploymentStatus.RETIRED;
        };
        if (member.status() != expected) {
            throw new BadRequestException("成员当前状态 " + member.status()
                    + " 与支付事由 " + reason + " 不一致，仅离职或退休成员可发起对应支付申请");
        }
    }
}
