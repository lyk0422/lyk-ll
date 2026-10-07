package com.example.starter.cosmetic.service;

import com.example.starter.cosmetic.web.dto.DestroyLabelsRequest;
import com.example.starter.cosmetic.web.dto.DestroyResponse;
import com.example.starter.cosmetic.web.dto.DrawLabelsRequest;
import com.example.starter.cosmetic.web.dto.DrawRecordResponse;
import com.example.starter.cosmetic.web.dto.DrawResponse;
import com.example.starter.cosmetic.web.dto.ExpectedDigestResponse;
import com.example.starter.cosmetic.web.dto.PrintRunResponse;
import com.example.starter.cosmetic.web.dto.PrintRunResultResponse;
import com.example.starter.cosmetic.web.dto.RegisterPrintRunRequest;

import java.time.Instant;
import java.util.List;

/**
 * 包装标签印次台账领域服务。
 *
 * <p>写操作以 requestId 幂等（同键同参重放首次结果，同键异参 409，失败不占键）；
 * 领用/销毁/禁用与备案换版、注销在同一事务边界内完成，余额与凭据可追溯。</p>
 */
public interface LabelPrintService {

    /** 登记印次：绑定产品当前有效备案的具体修订，核对标签摘要（儿童逐档）。 */
    PrintRunResultResponse register(RegisterPrintRunRequest request);

    /** 领用标签：重查当前有效备案，原子扣减未领余额并留凭据。 */
    DrawResponse draw(String printRunNo, DrawLabelsRequest request);

    /** 登记销毁未领标签：只减少未领余额，留证。 */
    DestroyResponse destroy(String printRunNo, DestroyLabelsRequest request);

    /** 查询印次台账（含张数守恒拆分与禁用剩余）。 */
    PrintRunResponse getPrintRun(String printRunNo);

    /** 查询印次全部领用凭据（追溯）。 */
    List<DrawRecordResponse> listDraws(String printRunNo);

    /** 查询产品当前有效备案的标签摘要期望（登记印次前核对口径）。 */
    ExpectedDigestResponse expectedDigest(String productCode);

    /**
     * 备案修订失效联动：原子禁用其全部 ACTIVE 印次剩余标签，
     * 并给已领凭据追加风险关联（不抹历史）。须在备案状态变更的事务内调用。
     *
     * @param filingNo        失效的备案编号
     * @param filingVersionNo 失效的备案修订号
     * @param reason          禁用原因（换版/注销等）
     * @param now             处理时刻(UTC)
     */
    void disableForFilingRevision(String filingNo, int filingVersionNo, String reason, Instant now);
}
