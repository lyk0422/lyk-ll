package com.example.starter.runway;

import java.time.Instant;

/**
 * 受控跑道行：对应 controlled_runway 表。
 *
 * @param runwayId  跑道编号（合成数据）
 * @param lengthMeters 跑道长度（单位：米，正整数）；存在活动施工后不许缩改
 * @param version   聚合乐观锁版本：从1开始，每次成功变更加1
 * @param requestId 登记幂等请求标识
 * @param createdAt 记录创建时间（UTC）
 * @param updatedAt 记录最近更新时间（UTC）
 */
public record ControlledRunway(
        String runwayId,
        int lengthMeters,
        int version,
        String requestId,
        Instant createdAt,
        Instant updatedAt) {
}
