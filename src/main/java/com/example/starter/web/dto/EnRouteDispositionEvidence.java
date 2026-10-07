package com.example.starter.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * 途中异常处置单完整证据：冻结的编组/列尾/原发车证据指针，途中试验与双人确认历史。
 *
 * @param id                 处置单ID
 * @param jobId              所属作业ID
 * @param status             处置状态
 * @param version            处置版本
 * @param consistVersion     登记时冻结的编组版本
 * @param frozenTailCarNo    登记时冻结的尾车车辆号
 * @param frozenEotBindingId 登记时冻结的生效列尾绑定ID；null表示未绑定列尾装置
 * @param reason             途中异常说明
 * @param resumedAt          续行结束时刻（UTC）；null表示仍阻断中
 * @param createdAt          创建时间（UTC）
 * @param tests              途中试验记录（按时间正序）
 * @param confirmations      双人确认记录（按时间正序）
 */
public record EnRouteDispositionEvidence(
        long id,
        long jobId,
        String status,
        int version,
        int consistVersion,
        String frozenTailCarNo,
        Long frozenEotBindingId,
        String reason,
        Instant resumedAt,
        Instant createdAt,
        List<TestEvidence> tests,
        List<ConfirmationEvidence> confirmations) {

    /**
     * 途中试验证据。
     *
     * @param id             试验ID
     * @param testType       试验类型
     * @param result         结论
     * @param consistVersion 试验时编组版本
     * @param voided         是否因途中编组变更失效
     * @param validUntil     有效截止时刻（UTC）
     * @param createdAt      创建时间（UTC）
     */
    public record TestEvidence(
            long id,
            String testType,
            String result,
            int consistVersion,
            boolean voided,
            Instant validUntil,
            Instant createdAt) {
    }

    /**
     * 双人确认证据。
     *
     * @param id             确认ID
     * @param consistVersion 确认时编组版本
     * @param tailCarNo      确认时尾车车辆号
     * @param confirmer1     确认人1
     * @param confirmer2     确认人2
     * @param valid          是否仍有效
     * @param createdAt      创建时间（UTC）
     */
    public record ConfirmationEvidence(
            long id,
            int consistVersion,
            String tailCarNo,
            String confirmer1,
            String confirmer2,
            boolean valid,
            Instant createdAt) {
    }
}
