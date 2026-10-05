package com.example.starter.feed.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 治疗性用药直报响应。
 *
 * @param requestId                  幂等键
 * @param batchNo                    批次号
 * @param accepted                   是否受理（目录外药品、单人直报等为 false）
 * @param rejectReason               拒绝原因；受理时为 null
 * @param late                       是否为出栏核销（终态）后的迟到直报（只记事件并告警）
 * @param alerted                    是否产生了合规告警
 * @param replayed                   是否为同键同参的幂等重放
 * @param drugName                   兽药名称快照；未受理为 null
 * @param withdrawalDays             兽药休药天数快照（UTC 日）；未受理为 null
 * @param administeredAtUtc          给药时刻（UTC）
 * @param withdrawalUntilDate        重算后休药期届满日（UTC 日历日）；无休药义务为 null
 * @param earliestSlaughterUtc       重算后最早合法出栏时刻（UTC）；无休药义务为 null
 * @param governingWithdrawalDays    重算后合并口径决定性最大休药天数；无休药义务为 null
 * @param revokedSlaughterRequestIds 本次直报触发撤销的出栏申请 requestId 列表；无撤销为空
 */
public record TreatmentReportResponse(
        String requestId,
        String batchNo,
        boolean accepted,
        String rejectReason,
        boolean late,
        boolean alerted,
        boolean replayed,
        String drugName,
        Integer withdrawalDays,
        Instant administeredAtUtc,
        LocalDate withdrawalUntilDate,
        Instant earliestSlaughterUtc,
        Integer governingWithdrawalDays,
        List<String> revokedSlaughterRequestIds
) {
}
