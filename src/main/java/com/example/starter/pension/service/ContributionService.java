package com.example.starter.pension.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.pension.dto.ContributionRequest;
import com.example.starter.pension.dto.ContributionResponse;
import com.example.starter.pension.repo.ContributionRepository;

/**
 * 缴费登记服务（合成数据）。计划结清后补登的历史缴费 used_by_plan_no 为空，
 * 将由补发流程受理，不影响原计划。
 */
@Service
public class ContributionService {

    private final ContributionRepository contributionRepository;
    private final MemberService memberService;

    public ContributionService(ContributionRepository contributionRepository, MemberService memberService) {
        this.contributionRepository = contributionRepository;
        this.memberService = memberService;
    }

    /**
     * 登记一笔企业或个人缴费。
     */
    @Transactional
    public ContributionResponse register(ContributionRequest request) {
        // 成员必须存在；缴费日期不做额外限制以支持“结清后补缴历史缴费”场景。
        memberService.requireMember(request.memberNo());
        long id = contributionRepository.insert(
                request.memberNo(), request.contributionDate(), request.source(), request.amountCents());
        return new ContributionResponse(id, request.memberNo(), request.contributionDate(),
                request.source(), request.amountCents(), null);
    }
}
