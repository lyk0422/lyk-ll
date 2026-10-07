package com.example.starter.cosmetic.domain;

import java.time.Instant;
import java.util.Map;

/**
 * 包装标签印次台账（持久化对象的内存镜像，字段口径与 label_print_run 表一致）。
 *
 * <p>印次登记时绑定已生效备案的具体修订（filingNo + filingVersionNo），
 * 并冻结标签文本摘要；儿童印次另冻结各年龄档独立标识摘要（bandDigests），
 * 逐档与备案受理冻结的标识文本一致，不允许只拿一个全局标识代替。</p>
 *
 * <p>张数守恒：printedCount = issuedCount + destroyedCount + remainingCount()。</p>
 *
 * @param bandDigests 儿童印次的分龄档独立标识摘要（bandCode -&gt; SHA-256）；成人印次为 null
 */
public record LabelPrintRun(
        Long id,
        String printRunNo,
        String productCode,
        String filingNo,
        int filingVersionNo,
        FilingCategory filingCategory,
        String labelDigest,
        Map<String, String> bandDigests,
        int printedCount,
        int issuedCount,
        int destroyedCount,
        PrintRunStatus status,
        int versionNo,
        String disabledReason,
        Instant disabledAtUtc,
        String requestId,
        Instant createdAtUtc,
        Instant updatedAtUtc
) {
    /** 未领余额（含禁用剩余）：printedCount - issuedCount - destroyedCount。 */
    public int remainingCount() {
        return printedCount - issuedCount - destroyedCount;
    }

    /** 禁用剩余：仅 DISABLED 状态的未领余额；ACTIVE 时为 0。 */
    public int disabledRemainingCount() {
        return status == PrintRunStatus.DISABLED ? remainingCount() : 0;
    }
}
