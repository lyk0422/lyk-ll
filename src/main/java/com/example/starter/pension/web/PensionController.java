package com.example.starter.pension.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.pension.dto.ContributionRequest;
import com.example.starter.pension.dto.ContributionResponse;
import com.example.starter.pension.dto.MemberRequest;
import com.example.starter.pension.dto.MemberResponse;
import com.example.starter.pension.dto.PaymentRequest;
import com.example.starter.pension.dto.PaymentResponse;
import com.example.starter.pension.dto.PlanResponse;
import com.example.starter.pension.dto.PayoutRequest;
import com.example.starter.pension.dto.SettleResponse;
import com.example.starter.pension.dto.SupplementRequest;
import com.example.starter.pension.service.ContributionService;
import com.example.starter.pension.service.MemberService;
import com.example.starter.pension.service.PayoutService;

import jakarta.validation.Valid;

/**
 * 企业年金权益归属与分期支付服务 REST 入口。
 */
@RestController
@RequestMapping("/api/pension")
public class PensionController {

    private final MemberService memberService;
    private final ContributionService contributionService;
    private final PayoutService payoutService;

    public PensionController(MemberService memberService,
                             ContributionService contributionService,
                             PayoutService payoutService) {
        this.memberService = memberService;
        this.contributionService = contributionService;
        this.payoutService = payoutService;
    }

    /** 登记成员（合成数据）。 */
    @PostMapping("/members")
    public ResponseEntity<MemberResponse> registerMember(@Valid @RequestBody MemberRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(memberService.register(request));
    }

    /** 登记缴费（合成数据）。 */
    @PostMapping("/contributions")
    public ResponseEntity<ContributionResponse> registerContribution(
            @Valid @RequestBody ContributionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(contributionService.register(request));
    }

    /** 发起支付申请：requestId 幂等，受理时冻结权益快照。 */
    @PostMapping("/payouts")
    public ResponseEntity<PlanResponse> apply(@Valid @RequestBody PayoutRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(payoutService.apply(request));
    }

    /** 结清后补缴的历史缴费走补发流程，引用原计划号。 */
    @PostMapping("/supplements")
    public ResponseEntity<PlanResponse> supplement(@Valid @RequestBody SupplementRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(payoutService.supplement(request));
    }

    /** 单期支付指令：requestId 幂等，失败可换新 requestId 重试。 */
    @PostMapping("/payments")
    public PaymentResponse pay(@Valid @RequestBody PaymentRequest request) {
        return payoutService.pay(request);
    }

    /** 查询计划详情（含权益快照与分期明细）。 */
    @GetMapping("/plans/{planNo}")
    public PlanResponse getPlan(@PathVariable String planNo) {
        return payoutService.getPlan(planNo);
    }

    /** 兜底/查询性结清；重复结清幂等成功。正常结清由末期出款自动触发。 */
    @PostMapping("/plans/{planNo}/settle")
    public SettleResponse settle(@PathVariable String planNo) {
        return payoutService.settle(planNo);
    }
}
