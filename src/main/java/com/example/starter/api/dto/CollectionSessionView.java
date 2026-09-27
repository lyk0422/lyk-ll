package com.example.starter.api.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 采集会话视图：含绑定信息、水位线、乐观锁版本与包状态统计。
 *
 * @param watermark       已连续入账的最大包序号，0=尚未入账
 * @param version         乐观锁版本，从 1 开始，成功变更加 1
 * @param bufferedCount   乱序缓冲中的包数（不参与自检/互检/见证放行）
 * @param consumedCount   已入账包数
 * @param lateCount       归档后迟到包数
 * @param points          会话绑定的紧固点清单快照
 * @param closedAt        关闭时间(UTC)，null=未关闭
 */
public record CollectionSessionView(
        String sessionNo,
        String cardNo,
        String toolCode,
        LocalDate calibrationDate,
        String status,
        int watermark,
        int version,
        int bufferedCount,
        int consumedCount,
        int lateCount,
        List<String> points,
        Instant createdAt,
        Instant closedAt) {
}
