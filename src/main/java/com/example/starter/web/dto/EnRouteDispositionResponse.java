package com.example.starter.web.dto;

import java.time.Instant;

/**
 * 途中异常处置单响应。
 *
 * @param id                 处置单ID
 * @param jobId              所属作业ID
 * @param status             处置状态：ACTIVE阻断中/RESUMED已续行结束
 * @param version            处置版本，自1起
 * @param consistVersion     登记时冻结的编组版本（原发车证据）
 * @param frozenTailCarNo    登记时冻结的尾车车辆号（原发车证据）
 * @param frozenEotBindingId 登记时冻结的生效列尾绑定ID；null表示未绑定列尾装置
 * @param reason             途中异常说明
 * @param resumedAt          续行结束时刻（UTC）；null表示仍阻断中
 * @param createdAt          创建时间（UTC）
 */
public record EnRouteDispositionResponse(
        long id,
        long jobId,
        String status,
        int version,
        int consistVersion,
        String frozenTailCarNo,
        Long frozenEotBindingId,
        String reason,
        Instant resumedAt,
        Instant createdAt) {
}
