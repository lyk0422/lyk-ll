package com.example.starter.repo;

import com.example.starter.domain.TestResult;
import com.example.starter.domain.TestType;

import java.time.Instant;

/**
 * 途中制动试验行记录。
 *
 * @param id                 途中试验主键
 * @param incidentId         绑定处置单ID
 * @param jobId              所属作业ID
 * @param consistVersion     试验对应编组版本
 * @param testType           试验类型
 * @param pressureReduction  减压量，单位 kPa
 * @param pipePressureBefore 试验前列车管风压，单位 kPa
 * @param result             结论
 * @param voided             是否因阻断中编组变更作废
 * @param validUntil         试验有效截止时刻（UTC）；恰到即失效
 * @param createdAt          创建时间（UTC）
 */
public record EnRouteTestRow(
        long id,
        long incidentId,
        long jobId,
        int consistVersion,
        TestType testType,
        int pressureReduction,
        int pipePressureBefore,
        TestResult result,
        boolean voided,
        Instant validUntil,
        Instant createdAt) {
}
