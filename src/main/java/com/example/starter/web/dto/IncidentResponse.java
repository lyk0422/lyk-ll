package com.example.starter.web.dto;

import java.time.Instant;

/**
 * 途中异常处置单视图。
 *
 * @param id             处置主键
 * @param jobId          所属作业ID
 * @param status         处置状态：OPEN 阻断中 / RESUMED 已续行
 * @param consistVersion 登记时冻结的编组版本（原发车证据）
 * @param eotBindingId   登记时冻结的生效列尾绑定ID；NULL 表示未启用列尾控制
 * @param version        处置版本，自 1 起；成功变更加 1
 * @param reason         途中异常现象说明
 * @param resumedAt      续行许可时刻（UTC）；NULL 表示仍阻断中
 * @param createdAt      登记时刻（UTC）
 * @param message        提示信息
 */
public record IncidentResponse(
        long id,
        long jobId,
        String status,
        int consistVersion,
        Long eotBindingId,
        int version,
        String reason,
        Instant resumedAt,
        Instant createdAt,
        String message) {
}
