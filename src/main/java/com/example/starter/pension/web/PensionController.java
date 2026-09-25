package com.example.starter.pension.web;

import com.example.starter.pension.api.ContributionRequest;
import com.example.starter.pension.api.MemberUpsertRequest;
import com.example.starter.pension.api.PaymentInstructionRequest;
import com.example.starter.pension.api.PaymentResultResponse;
import com.example.starter.pension.api.PayoutRequest;
import com.example.starter.pension.api.PlanResponse;
import com.example.starter.pension.api.SettleResponse;
import com.example.starter.pension.api.SupplementaryPayoutRequest;
import com.example.starter.pension.service.PensionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 企业年金权益归属与分期支付 REST 入口。
 */
@RestController
@RequestMapping("/api/pension")
public class PensionController {

    private final PensionService pensionService;

    public PensionController(PensionService pensionService) {
        this.pensionService = pensionService;
    }

    /** 登记/更新成员（合成数据）。 */
    @PostMapping("/members")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void upsertMember(@Valid @RequestBody MemberUpsertRequest request) {
        pensionService.upsertMember(request);
    }

    /** 录入缴费（合成数据）。 */
    @PostMapping("/contributions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void addContribution(@Valid @RequestBody ContributionRequest request) {
        pensionService.addContribution(request);
    }

    /** 受理支付申请。 */
    @PostMapping("/payouts")
    @ResponseStatus(HttpStatus.CREATED)
    public PlanResponse applyPayout(@Valid @RequestBody PayoutRequest request) {
        return pensionService.applyPayout(request);
    }

    /** 受理结清后补缴的补发申请（引用原计划号）。 */
    @PostMapping("/payouts/supplementary")
    @ResponseStatus(HttpStatus.CREATED)
    public PlanResponse applySupplementary(@Valid @RequestBody SupplementaryPayoutRequest request) {
        return pensionService.applySupplementary(request);
    }

    /** 查询计划与各期次。 */
    @GetMapping("/plans/{planNo}")
    public PlanResponse getPlan(@PathVariable String planNo) {
        return pensionService.getPlan(planNo);
    }

    /** 执行单期支付指令。 */
    @PostMapping("/payments")
    public PaymentResultResponse pay(@Valid @RequestBody PaymentInstructionRequest request) {
        return pensionService.payInstallment(request);
    }

    /** 结清计划（全部期次完成时）。 */
    @PostMapping("/plans/{planNo}/settle")
    public SettleResponse settle(@PathVariable String planNo) {
        return pensionService.settle(planNo);
    }
}
