package com.example.starter.pension.service;

import com.example.starter.pension.api.ContributionRequest;
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
}
