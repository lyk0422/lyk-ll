package com.example.starter.cosmetic.web.dto;

import com.example.starter.cosmetic.domain.PrintRunRecord;
import com.example.starter.cosmetic.domain.PrintRunStatus;

import java.time.Instant;
import java.util.Map;

/**
 * 包装标签印次台账响应。
 *
 * <p>张数守恒可核对：printedCount = drawnCount + destroyedCount + remainingCount；
 * 禁用印次的剩余以 disabledRemainingCount 单独区分（ACTIVE 时为 0）。</p>
 *
 * @param printRunNo             印次号
 * @param productCode            产品编码
 * @param filingNo               绑定的备案编号
 * @param filingVersionNo        绑定的备案修订号
 * @param labelDigest            标签文本摘要（儿童为逐档摘要集合的结构化摘要）
 * @param bandDigests            儿童印次逐档标识摘要（分龄档编码-&gt;摘要）；成人为 null
 * @param printedCount           印刷总张数
 * @param drawnCount             已领用张数
 * @param destroyedCount         已销毁张数
 * @param remainingCount         未领余额（含禁用剩余）
 * @param disabledRemainingCount 禁用剩余张数（仅 DISABLED 时等于未领余额，否则为 0）
 * @param status                 状态：ACTIVE / DISABLED
 * @param version                印次台账版本（1 起，每次修改 +1）
 * @param disabledReason         禁用原因；未禁用为 null
 * @param disabledAtUtc          禁用时刻(UTC)；未禁用为 null
 * @param createdAtUtc           登记时刻(UTC)
 * @param updatedAtUtc           最近修改时刻(UTC)
 */
public record PrintRunResponse(
        String printRunNo,
        String productCode,
        String filingNo,
        int filingVersionNo,
        String labelDigest,
        Map<String, String> bandDigests,
        int printedCount,
        int drawnCount,
        int destroyedCount,
        int remainingCount,
        int disabledRemainingCount,
        String status,
        int version,
        String disabledReason,
        Instant disabledAtUtc,
        Instant createdAtUtc,
        Instant updatedAtUtc) {

    /** 由持久化记录构造响应。 */
    public static PrintRunResponse from(PrintRunRecord r) {
        int remaining = r.remainingCount();
        return new PrintRunResponse(
                r.printRunNo(), r.productCode(), r.filingNo(), r.filingVersionNo(),
                r.labelDigest(), r.bandDigests(),
                r.printedCount(), r.drawnCount(), r.destroyedCount(), remaining,
                r.status() == PrintRunStatus.DISABLED ? remaining : 0,
                r.status().name(), r.version(),
                r.disabledReason(), r.disabledAtUtc(), r.createdAtUtc(), r.updatedAtUtc());
    }
}
