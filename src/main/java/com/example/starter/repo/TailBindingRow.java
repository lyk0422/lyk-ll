package com.example.starter.repo;

import com.example.starter.domain.BindingStatus;
import com.example.starter.domain.ReleaseReason;

import java.time.LocalDateTime;

/**
 * 列尾装置绑定历史行记录。
 *
 * @param id             绑定主键
 * @param jobId          所属作业ID
 * @param deviceId       装置主键
 * @param deviceNo       装置编号
 * @param tailCarNo      绑定冻结的编组尾车车辆号
 * @param consistVersion 绑定冻结的编组版本
 * @param status         绑定状态：ACTIVE 生效中 / RELEASED 已解除
 * @param releaseReason  解除原因；NULL 生效中
 * @param createdAt      绑定时间（JVM 时区）
 * @param releasedAt     解除时间（JVM 时区）；NULL 生效中
 */
public record TailBindingRow(
        long id,
        long jobId,
        long deviceId,
        String deviceNo,
        String tailCarNo,
        int consistVersion,
        BindingStatus status,
        ReleaseReason releaseReason,
        LocalDateTime createdAt,
        LocalDateTime releasedAt) {
}
