package com.example.starter.repo;

import com.example.starter.domain.JobStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 列车作业行记录。
 *
 * @param id             作业主键
 * @param trainNo        车次（合成数据）
 * @param track          股道号
 * @param status         作业状态
 * @param consistVersion 当前编组版本
 * @param carCount       在列辆数
 * @param totalLength    总换长
 * @param totalWeight    总重，单位吨
 * @param consistChecked 当前编组版本是否已通过运统1核对
 * @param createdAt      创建时间（JVM 时区）
 */
public record JobRow(
        long id,
        String trainNo,
        String track,
        JobStatus status,
        int consistVersion,
        int carCount,
        BigDecimal totalLength,
        BigDecimal totalWeight,
        boolean consistChecked,
        LocalDateTime createdAt) {
}
