package com.example.starter.web.dto;

import java.time.Instant;

/**
 * 途中制动试验响应。
 *
 * @param id              途中试验主键
 * @param incidentId      绑定处置单ID
 * @param testType        试验类型
 * @param result          结论：PASS 合格 / FAIL 不合格
 * @param consistVersion  试验对应编组版本
 * @param validUntil      试验有效截止时刻（UTC）；恰到即失效
 * @param incidentVersion 处置单新版本
 * @param message         提示信息
 */
public record EnRouteTestResponse(
        long id,
        long incidentId,
        String testType,
        String result,
        int consistVersion,
        Instant validUntil,
        int incidentVersion,
        String message) {
}
