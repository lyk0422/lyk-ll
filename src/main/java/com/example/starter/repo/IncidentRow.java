package com.example.starter.repo;

import com.example.starter.domain.IncidentStatus;

import java.time.Instant;

/**
 * 途中异常处置单行记录。
 *
 * @param id             处置主键
 * @param jobId          所属作业ID
 * @param consistVersion 登记时冻结的编组版本（原发车证据）
 * @param eotBindingId   登记时冻结的生效列尾绑定ID；NULL 表示未启用列尾控制
 * @param reason         途中异常现象说明
 * @param status         处置状态：OPEN 阻断中 / RESUMED 已续行
 * @param version        处置版本，自 1 起；成功变更加 1，旧版 409
 * @param requestId      幂等请求键
 * @param resumedAt      续行许可时刻（UTC）；NULL 表示仍阻断中
 * @param createdAt      登记时刻（UTC）
 */
public record IncidentRow(
        long id,
        long jobId,
        int consistVersion,
        Long eotBindingId,
        String reason,
        IncidentStatus status,
        int version,
        String requestId,
        Instant resumedAt,
        Instant createdAt) {
}
