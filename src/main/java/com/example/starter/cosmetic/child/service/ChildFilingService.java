package com.example.starter.cosmetic.child.service;

import com.example.starter.cosmetic.child.web.dto.AgeRuleResponse;
import com.example.starter.cosmetic.child.web.dto.ChildChangeRequest;
import com.example.starter.cosmetic.child.web.dto.ChildCorrectionRequest;
import com.example.starter.cosmetic.child.web.dto.ChildFilingResponse;
import com.example.starter.cosmetic.child.web.dto.ChildFilingResultResponse;
import com.example.starter.cosmetic.child.web.dto.ChildRevisionResponse;
import com.example.starter.cosmetic.child.web.dto.ChildSubmitRequest;
import com.example.starter.cosmetic.web.dto.ReviewDetailResponse;

import java.time.Instant;
import java.util.List;

/**
 * 儿童化妆品分龄备案领域服务：提交、分龄资料审查、补正、变更、公示期满生效与证据查询。
 *
 * <p>写操作幂等：requestId 为键，指纹覆盖完整参数（含版本、集合与精确时刻）；
 * 同键同参重放首次冻结结果，同键异参 409，业务/参数失败不占键。
 * 业务、版本（expectedVersion 乐观锁）与幂等结果同事务提交。</p>
 */
public interface ChildFilingService {

    /** 儿童备案提交（同步分龄审查：通过受理并公示，不通过零生效且缺项一次列全）。 */
    ChildFilingResultResponse submit(ChildSubmitRequest request);

    /** 材料补正：生成新修订并使旧修订待审批准失效（与公示期满竞争唯一结果）。 */
    ChildFilingResultResponse correct(String filingNo, ChildCorrectionRequest request);

    /** 备案变更（仅生效备案）：儿童属性/年龄/标识适用范围变化必须实质变更。 */
    ChildFilingResultResponse change(String filingNo, ChildChangeRequest request);

    /** 查询儿童备案（含当前最新修订）。 */
    ChildFilingResponse getFiling(String filingNo);

    /** 查询备案全部修订链。 */
    List<ChildRevisionResponse> getRevisions(String filingNo);

    /** 判定证据查询（revisionNo 为 null 时返回全部修订的证据）。 */
    List<ReviewDetailResponse> getEvidence(String filingNo, Integer revisionNo);

    /** 查询当前启用的分龄规则（合成初始化内容）。 */
    AgeRuleResponse getAgeRules();

    /** 扫描并原子生效已期满公示（恰第 10 日 24:00 UTC 起可生效），返回生效数量。 */
    int expireDuePublicities(Instant now);
}
