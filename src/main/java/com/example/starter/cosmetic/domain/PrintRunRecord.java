package com.example.starter.cosmetic.domain;

import java.time.Instant;
import java.util.Map;

/**
 * 包装标签印次台账（持久化对象的内存镜像，字段口径与 label_print_run 表一致）。
 *
 * <p>印次绑定已生效备案的具体修订（filingNo + filingVersionNo）；
 * 儿童印次冻结各年龄档独立标识摘要（bandDigests，分龄档编码-&gt;该档摘要），
 * 成人印次 bandDigests 为 null。所有时刻字段均为 UTC。</p>
 *
 * <p>张数守恒口径：printedCount = drawnCount + destroyedCount + 剩余。</p>
 */
public record PrintRunRecord(
        Long id,
        String printRunNo,
        String productCode,
        String filingNo,
        int filingVersionNo,
        String labelDigest,
        Map<String, String> bandDigests,
        int printedCount,
        int drawnCount,
        int destroyedCount,
        PrintRunStatus status,
        int version,
        String requestId,
        String disabledReason,
        Instant disabledAtUtc,
        Instant createdAtUtc,
        Instant updatedAtUtc
) {
    /** 未领余额（含禁用剩余）：印刷总数 - 已领 - 已毁。 */
    public int remainingCount() {
        return printedCount - drawnCount - destroyedCount;
    }
}
