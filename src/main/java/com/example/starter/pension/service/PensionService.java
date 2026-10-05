package com.example.starter.pension.service;

import com.example.starter.pension.api.ContributionRequest;
import com.example.starter.pension.api.InheritanceClaimRequest;
import com.example.starter.pension.api.InheritanceClaimResponse;
import com.example.starter.pension.api.InheritanceCompleteResponse;
import com.example.starter.pension.api.LateContributionEventView;
import com.example.starter.pension.api.MemberUpsertRequest;
import com.example.starter.pension.api.PaymentInstructionRequest;
import com.example.starter.pension.api.PaymentResultResponse;
import com.example.starter.pension.api.PayoutRequest;
import com.example.starter.pension.api.PlanResponse;
import com.example.starter.pension.api.SettleResponse;
import com.example.starter.pension.api.SupplementaryPayoutRequest;

/**
 * 企业年金权益归属与分期支付应用服务。
 */
public interface PensionService {

    /** 登记/更新成员（合成数据）。 */
    void upsertMember(MemberUpsertRequest request);

    /** 录入缴费（合成数据）。 */
    void addContribution(ContributionRequest request);

    /**
     * 受理支付申请（含司龄归属计算、权益快照冻结、分期计划生成、未归属划回预留）。
     * 以 requestId 幂等：同键同参返回首次计划，同键异参返回 409。
     */
    PlanResponse applyPayout(PayoutRequest request);

    /**
     * 结清后补缴历史缴费的补发申请，生成引用原计划号的新计划，不改动原计划。
     */
    PlanResponse applySupplementary(SupplementaryPayoutRequest request);

    /** 查询计划（含各期次）。 */
    PlanResponse getPlan(String planNo);

    /**
     * 执行单期支付指令，以 requestId 幂等；失败可换新 requestId 重试，每期最多出款一次。
     * 最后一期出款与未归属企业缴费划回同事务，并自动结清计划。
     */
    PaymentResultResponse payInstallment(PaymentInstructionRequest request);

    /**
     * 结清计划：全部期次完成时结清（通常由末期支付自动触发）；
     * 对已结清计划重复结清返回幂等成功。
     */
    SettleResponse settle(String planNo);

    /**
     * 受理继承申领：继承人于成员身故日起 180 个自然日（UTC 日界，含第 180 日）内
     * 申请一次性继承支付。继承金额=账户余额+未支付分期本金，申报两项合计与继承支付额
     * 必须勾稽一致，不等拒绝。存在进行中分期计划时受理即在同一事务内终止剩余分期
     * （已支付部分不回溯）。以 requestId 幂等：同键同参返回首次结果，同键异参 409；
     * 同一成员并发申请仅唯一受理。
     */
    InheritanceClaimResponse applyInheritanceClaim(InheritanceClaimRequest request);

    /** 查询继承申领（含台账勾稽明细，可复算）。 */
    InheritanceClaimResponse getInheritanceClaim(String claimNo);

    /**
     * 完成继承支付（终态）：出款成功置 PAID；重复完成返回幂等成功；
     * 出款失败时申领保持 ACCEPTED 可重试。
     */
    InheritanceCompleteResponse completeInheritancePayout(String claimNo, boolean forceFail);

    /** 查询成员在继承支付完成后迟到缴费的事件列表（提示人工复核）。 */
    java.util.List<LateContributionEventView> listLateContributionEvents(String memberNo);
}
