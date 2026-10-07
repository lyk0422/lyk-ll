package com.example.starter.cosmetic.web.dto;

import com.example.starter.cosmetic.domain.LabelPrintRun;

import java.time.Instant;
import java.util.Map;

/**
 * 包装标签印次台账响应。时间均为 UTC。
 *
 * <p>张数守恒可核对：printedCount = issuedCount + destroyedCount + remainingCount；
 * disabledRemainingCount 单独区分禁用剩余（ACTIVE 时为 0）。</p>
 */
public record PrintRunResponse(
        String printRunNo,
        String productCode,
        String filingNo,
        int filingVersionNo,
        String filingCategory,
        String labelDigest,
        Map<String, String> bandDigests,
        int printedCount,
        int issuedCount,
        int destroyedCount,
        int remainingCount,
        int disabledRemainingCount,
        String status,
        int versionNo,
        String disabledReason,
        Instant disabledAtUtc,
        Instant createdAtUtc,
        Instant updatedAtUtc) {

    /** 由领域记录构造响应。 */
    public static PrintRunResponse from(LabelPrintRun r) {
        return new PrintRunResponse(
                r.printRunNo(), r.productCode(), r.filingNo(), r.filingVersionNo(),
                r.filingCategory().name(), r.labelDigest(), r.bandDigests(),
                r.printedCount(), r.issuedCount(), r.destroyedCount(),
                r.remainingCount(), r.disabledRemainingCount(),
                r.status().name(), r.versionNo(),
                r.disabledReason(), r.disabledAtUtc(), r.createdAtUtc(), r.updatedAtUtc());
    }
}
