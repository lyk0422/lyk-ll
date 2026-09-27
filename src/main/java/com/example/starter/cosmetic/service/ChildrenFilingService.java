package com.example.starter.cosmetic.service;

import com.example.starter.cosmetic.web.dto.ChangeChildrenFilingRequest;
import com.example.starter.cosmetic.web.dto.ChildrenEvidenceResponse;
import com.example.starter.cosmetic.web.dto.ChildrenFilingResponse;
import com.example.starter.cosmetic.web.dto.ChildrenFilingResultResponse;
import com.example.starter.cosmetic.web.dto.CorrectChildrenFilingRequest;
import com.example.starter.cosmetic.web.dto.SubmitChildrenFilingRequest;

import java.time.Instant;
import java.util.List;

/**
 * 儿童化妆品备案领域服务：分龄资料审查、材料补正、变更、公示期满生效与判定证据查询。
 *
 * <p>所有写操作以 requestId 幂等（同键同参重放首次冻结结果，异参 409，失败不占键），
 * 修改携带 expectedVersion（版本从1开始，成功修改加1），业务、版本与幂等结果同事务。</p>
 */
public interface ChildrenFilingService {

    /** 儿童备案提交（同步分龄审查：通过受理并进入公示，缺项一次列全并零生效）。 */
    ChildrenFilingResultResponse submit(SubmitChildrenFilingRequest request);

    /** 材料补正：生成新修订并使旧修订待审批准失效，公示只使用最新修订。 */
    ChildrenFilingResultResponse correct(String filingNo, CorrectChildrenFilingRequest request);

    /** 儿童备案变更（儿童属性/年龄/标识适用范围改变一律实质变更）。 */
    ChildrenFilingResultResponse change(String productCode, ChangeChildrenFilingRequest request);

    /** 查询儿童备案最新修订。 */
    ChildrenFilingResponse getFiling(String filingNo);

    /** 查询产品儿童备案历史链。 */
    List<ChildrenFilingResponse> getProductHistory(String productCode);

    /** 判定证据查询：受理冻结的报告清单、分龄规则版本、标识文本摘要及审查明细。 */
    ChildrenEvidenceResponse getEvidence(String filingNo);

    /** 扫描并原子生效已期满公示（恰第 10 日 24:00 UTC 起可生效），返回生效数量。 */
    int expireDuePublicNotices(Instant now);
}
