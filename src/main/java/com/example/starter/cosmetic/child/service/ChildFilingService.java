package com.example.starter.cosmetic.child.service;

import com.example.starter.cosmetic.child.web.dto.ChildChangeFilingRequest;
import com.example.starter.cosmetic.child.web.dto.ChildCorrectFilingRequest;
import com.example.starter.cosmetic.child.web.dto.ChildFilingResponse;
import com.example.starter.cosmetic.child.web.dto.ChildFilingResultResponse;
import com.example.starter.cosmetic.child.web.dto.ChildReviewDetailResponse;
import com.example.starter.cosmetic.child.web.dto.ChildRuleResponse;
import com.example.starter.cosmetic.child.web.dto.ChildSubmitFilingRequest;

import java.time.Instant;
import java.util.List;

/**
 * 儿童化妆品分龄备案领域服务：首次提交、材料补正、实质/简易变更、公示期满生效与证据查询。
 */
public interface ChildFilingService {

    /** 儿童备案首次提交（同步完成分龄形式审查：通过进入公示，不通过驳回，零生效）。 */
    ChildFilingResultResponse submit(ChildSubmitFilingRequest request);

    /** 材料补正：沿用同号生成新修订（version+1）并原子失效旧待审修订；仅最新修订可公示。 */
    ChildFilingResultResponse correct(String filingNo, ChildCorrectFilingRequest request);

    /** 备案变更（自动判定实质/简易）；儿童属性、年龄、标识范围改变必为实质变更。 */
    ChildFilingResultResponse change(String productCode, ChildChangeFilingRequest request);

    /** 扫描并原子生效已期满公示（恰第 10 日 24:00 UTC 起），仅最新修订可生效，返回生效数量。 */
    int expireDuePublicNotices(Instant now);

    /** 查询儿童备案最新修订。 */
    ChildFilingResponse getFiling(String filingNo);

    /** 查询产品历史儿童备案修订链。 */
    List<ChildFilingResponse> getProductHistory(String productCode);

    /** 查询分龄审查明细（versionNo 为 null 返回该编号全部修订明细），供判定证据复算。 */
    List<ChildReviewDetailResponse> getReviews(String filingNo, Integer versionNo);

    /** 查询当前分龄合成规则（版本、分档、各档限值），即受理冻结依据与合成初始化结果。 */
    ChildRuleResponse getRule();
}
