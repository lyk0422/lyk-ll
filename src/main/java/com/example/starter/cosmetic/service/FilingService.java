package com.example.starter.cosmetic.service;

import com.example.starter.cosmetic.web.dto.AgeRuleResponse;
import com.example.starter.cosmetic.web.dto.ChangeFilingRequest;
import com.example.starter.cosmetic.web.dto.ChildEvidenceResponse;
import com.example.starter.cosmetic.web.dto.CorrectionFilingRequest;
import com.example.starter.cosmetic.web.dto.FilingResponse;
import com.example.starter.cosmetic.web.dto.FilingResultResponse;
import com.example.starter.cosmetic.web.dto.ReviewDetailResponse;
import com.example.starter.cosmetic.web.dto.SubmitFilingRequest;

import java.time.Instant;
import java.util.List;

/**
 * 化妆品备案领域服务：提交、形式审查、公示、异议、生效、变更、注销。
 */
public interface FilingService {

    /** 备案提交（同步完成形式审查：通过进入公示，不通过驳回）。 */
    FilingResultResponse submit(SubmitFilingRequest request);

    /** 备案变更（自动判定实质/简易）。 */
    FilingResultResponse change(String productCode, ChangeFilingRequest request);

    /**
     * 公示期内受理异议；与期满生效并发竞争，仅一个分支成功。
     *
     * @param atUtc 异议受理时刻（UTC），null 表示当前时钟
     */
    FilingResultResponse acceptObjection(String filingNo, Instant atUtc, String reason);

    /**
     * 异议审查结论：成立则终态驳回，不成立则回到公示（期满口径继续按原起算日计算）。
     */
    FilingResultResponse resolveObjection(String filingNo, boolean objectionUpheld, String conclusion);

    /** 注销（终态，重复注销幂等成功）。 */
    FilingResultResponse withdraw(String filingNo, String requestId, String reason);

    /** 查询备案最新版本。 */
    FilingResponse getFiling(String filingNo);

    /** 查询产品历史备案链。 */
    List<FilingResponse> getProductHistory(String productCode);

    /** 查询审查明细（versionNo 为 null 时返回该备案编号全部版本明细），供复算。 */
    List<ReviewDetailResponse> getReviews(String filingNo, Integer versionNo);

    /** 扫描并原子生效已期满公示（恰第 10 日 24:00 UTC 起可生效），返回生效数量。 */
    int expireDuePublicNotices(Instant now);

    /**
     * 儿童备案材料补正：仅允许对最新修订处于待补正（形式审查未通过）状态的儿童备案。
     * 生成新修订（版本号 +1）并使旧修订待审批准失效（同事务）；公示只能使用最新修订。
     * 审查不通过时新修订仍为待补正，缺项一次列全并零生效。
     */
    FilingResultResponse correct(String filingNo, CorrectionFilingRequest request);

    /** 儿童备案判定证据查询：受理冻结的报告清单、分龄规则版本、标识文本摘要及审查明细。 */
    ChildEvidenceResponse getChildEvidence(String filingNo, Integer versionNo);

    /** 儿童分龄规则查询（合成初始化结果：当前规则版本与分龄档定义）。 */
    AgeRuleResponse getAgeRule();
}
