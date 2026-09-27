package com.example.starter.repo;

import java.time.Instant;
import java.time.LocalDateTime;

/**
 * 合成列尾装置档案行记录。
 *
 * @param id                  装置主键
 * @param deviceNo            装置编号（合成数据）
 * @param selfCheckExpiresAt  自检合格有效截止时刻（UTC）；now 小于该时刻才有效，恰达即失效
 * @param version             递增版本，建档为 1，每次成功绑定/确认/解绑/销记释放加 1
 * @param boundJobId          当前绑定作业ID；NULL 表示空闲
 * @param createdAt           创建时间（JVM 时区）
 * @param updatedAt           更新时间（JVM 时区）
 */
public record TailDeviceRow(
        long id,
        String deviceNo,
        Instant selfCheckExpiresAt,
        int version,
        Long boundJobId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
