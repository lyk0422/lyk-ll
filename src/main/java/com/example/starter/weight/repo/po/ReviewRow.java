package com.example.starter.weight.repo.po;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 复检申诉行。时间均为 UTC。
 *
 * @param id                  主键
 * @param requestId           复检申请幂等请求标识
 * @param fingerprint         申请幂等指纹
 * @param caseId              关联案件主键
 * @param decisionId          被复检的处罚决定主键
 * @param reason              申诉理由
 * @param status              复检状态（UNDER_REVIEW/UPHELD/OVERTURNED）
 * @param appliedAt           申请时间（UTC）
 * @param concludedAt         结论时间（UTC）；未结论为 null
 * @param concludeRequestId   结论幂等请求标识；未结论为 null
 * @param concludeFingerprint 结论幂等指纹；未结论为 null
 * @param recheckTotalKg      复检实测总重快照（千克）；未结论为 null
 */
public record ReviewRow(
        Long id,
        String requestId,
        String fingerprint,
        Long caseId,
        Long decisionId,
        String reason,
        String status,
        Instant appliedAt,
        Instant concludedAt,
        String concludeRequestId,
        String concludeFingerprint,
        BigDecimal recheckTotalKg) {
}
