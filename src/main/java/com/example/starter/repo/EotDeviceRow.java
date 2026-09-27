package com.example.starter.repo;

import java.time.Instant;

/**
 * 列尾装置行记录。
 *
 * @param id                  装置主键
 * @param deviceNo            装置编号
 * @param selfCheckValidUntil 自检合格有效截止时刻（UTC）
 * @param boundJobId          当前绑定作业ID；null 表示空闲
 * @param version             装置状态递增版本（自1起）
 * @param createdAt           建档时间（UTC）
 */
public record EotDeviceRow(
        long id,
        String deviceNo,
        Instant selfCheckValidUntil,
        Long boundJobId,
        int version,
        Instant createdAt) {
}
