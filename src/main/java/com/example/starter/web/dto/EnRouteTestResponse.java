package com.example.starter.web.dto;

import java.time.Instant;

/**
 * 途中制动试验响应。
 *
 * @param id                 途中试验ID
 * @param dispositionId      所属处置单ID
 * @param testType           试验类型
 * @param result             结论：PASS/FAIL
 * @param consistVersion     试验时编组版本
 * @param validUntil         试验有效截止时刻（UTC）；恰达失效
 * @param dispositionVersion 处置版本（本次修改后）
 * @param message            结论说明
 */
public record EnRouteTestResponse(
        long id,
        long dispositionId,
        String testType,
        String result,
        int consistVersion,
        Instant validUntil,
        int dispositionVersion,
        String message) {
}
