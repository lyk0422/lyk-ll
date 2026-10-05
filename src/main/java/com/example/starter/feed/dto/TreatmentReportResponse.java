package com.example.starter.feed.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 治疗性用药直报响应。
 *
 * @param requestId                幂等键
 * @param batchNo                  批次号
 * @param replayed                 是否为同键同参的幂等重放
 * @param accepted                 是否受理（目录外药品名称、单人直报等拒绝受理时为 false）
 * @param rejectReason             拒绝受理原因；受理时为 null
 * @param late                     是否为出栏核销后的迟到直报（只记事件并告警）
 * @param alerted                  是否产生了合规告警
 * @param administeredAtUtc        最晚给药时刻（UTC）
 * @param governingWithdrawalDays  重算后合并口径决定性最大休药天数（UTC 日）；无义务为 null
 * @param withdrawalUntilDate      重算后休药期届满日（UTC 日历日）；无义务为 null
 * @param earliestSlaughterUtc     重算后最早合法出栏时刻（UTC）；无义务为 null
 * @param revokedApplicationIds    本次重算自动撤销的已批准出栏申请主键；无撤销为空列表
 */
public record TreatmentReportResponse(
        String requestId,
        String batchNo,
        boolean replayed,
        boolean accepted,
        String rejectReason,
        boolean late,
        boolean alerted,
        Instant administeredAtUtc,
        Integer governingWithdrawalDays,
        LocalDate withdrawalUntilDate,
        Instant earliestSlaughterUtc,
        List<Long> revokedApplicationIds
) {
}
