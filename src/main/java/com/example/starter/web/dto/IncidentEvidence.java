package com.example.starter.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * 途中异常处置完整证据：处置单、途中试验记录与双人确认历史（始发与途中两条证据均保留）。
 *
 * @param id             处置主键
 * @param jobId          所属作业ID
 * @param status         处置状态：OPEN 阻断中 / RESUMED 已续行
 * @param consistVersion 登记时冻结的编组版本（原发车证据）
 * @param eotBindingId   登记时冻结的生效列尾绑定ID；NULL 表示未启用列尾控制
 * @param version        处置版本
 * @param reason         途中异常现象说明
 * @param resumedAt      续行许可时刻（UTC）；NULL 表示仍阻断中
 * @param createdAt      登记时刻（UTC）
 * @param tests          途中试验记录（含已作废）
 * @param confirmations  途中双人确认历史（含已失效）
 */
public record IncidentEvidence(
        long id,
        long jobId,
        String status,
        int consistVersion,
        Long eotBindingId,
        int version,
        String reason,
        Instant resumedAt,
        Instant createdAt,
        List<TestPoint> tests,
        List<ConfirmationPoint> confirmations) {

    /**
     * 途中试验证据点。
     */
    public record TestPoint(
            long id,
            String testType,
            int pressureReduction,
            String result,
            int consistVersion,
            boolean voided,
            Instant validUntil,
            Instant createdAt) {
    }

    /**
     * 途中双人确认证据点。
     */
    public record ConfirmationPoint(
            long id,
            String tailCarNo,
            int consistVersion,
            String confirmer1,
            String confirmer2,
            boolean valid,
            Instant createdAt) {
    }
}
