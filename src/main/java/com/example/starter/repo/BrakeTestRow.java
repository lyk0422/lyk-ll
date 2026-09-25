package com.example.starter.repo;

import com.example.starter.domain.TestResult;
import com.example.starter.domain.TestType;

import java.time.LocalDateTime;

/**
 * 制动试验行记录。
 *
 * @param id                 试验主键
 * @param jobId              所属作业ID
 * @param consistVersion     试验对应编组版本
 * @param testType           试验类型
 * @param pressureReduction  减压量，单位 kPa
 * @param pipePressureBefore 试验前列车管风压，单位 kPa
 * @param result             结论
 * @param voided             是否因编组变更作废
 * @param createdAt          创建时间（JVM 时区）
 */
public record BrakeTestRow(
        long id,
        long jobId,
        int consistVersion,
        TestType testType,
        int pressureReduction,
        int pipePressureBefore,
        TestResult result,
        boolean voided,
        LocalDateTime createdAt) {
}
