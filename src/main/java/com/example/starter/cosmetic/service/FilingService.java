package com.example.starter.cosmetic.service;

import com.example.starter.cosmetic.web.dto.ChangeFilingRequest;
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
}
