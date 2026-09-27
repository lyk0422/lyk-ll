package com.example.starter.repo;

import com.example.starter.domain.EotBindingStatus;

import java.time.Instant;

/**
 * 列尾绑定行记录（含已解除历史）。
 *
 * @param id              绑定主键
 * @param deviceId        装置ID
 * @param deviceNo        装置编号
 * @param jobId           受控作业ID
 * @param tailCarNo       冻结的尾车车辆号
 * @param consistVersion  冻结的编组版本
 * @param status          绑定状态
 * @param activeDeviceId  ACTIVE 时等于装置ID，否则 null
 * @param activeJobId     ACTIVE 时等于作业ID，否则 null
 * @param requestId       绑定幂等请求键
 * @param releasedAt      解除时刻（UTC）；null 表示仍绑定中
 * @param createdAt       创建时间（UTC）
 */
public record EotBindingRow(
        long id,
        long deviceId,
        String deviceNo,
        long jobId,
        String tailCarNo,
        int consistVersion,
        EotBindingStatus status,
        Long activeDeviceId,
        Long activeJobId,
        String requestId,
        Instant releasedAt,
        Instant createdAt) {
}
