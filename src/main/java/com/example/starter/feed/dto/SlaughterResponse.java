package com.example.starter.feed.dto;

import java.time.Instant;

/**
 * 出栏申请响应。
 *
 * @param batchNo              批次号
 * @param approved             是否准许出栏
 * @param replayed             是否为同键同参的幂等重放
 * @param status               申请后批次状态：FEEDING / SLAUGHTERED
 * @param earliestSlaughterUtc 最早合法出栏时刻（UTC）；无休药义务时为 null
 * @param rejectReason         拒绝原因（休药期内/动物种类不符等）；准许时为 null
 * @param slaughteredAtUtc     实际出栏时刻（UTC）；未出栏为 null
 * @param appliedAtUtc         本次申请时刻（UTC）
 */
public record SlaughterResponse(
        String batchNo,
        boolean approved,
        boolean replayed,
        String status,
        Instant earliestSlaughterUtc,
        String rejectReason,
        Instant slaughteredAtUtc,
        Instant appliedAtUtc
) {
}
