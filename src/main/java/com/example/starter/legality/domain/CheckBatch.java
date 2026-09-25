package com.example.starter.legality.domain;

import java.time.OffsetDateTime;

/**
 * 排班校验批次。
 *
 * @param batchId       批次ID
 * @param status        批次状态
 * @param legal         整组判定：{@code true} 全部成员合法，{@code false} 存在违规成员
 * @param createdTime   创建时刻，UTC
 * @param finalizedTime 封板时刻，UTC；{@code null} 表示未封板
 */
public record CheckBatch(
        String batchId,
        BatchStatus status,
        boolean legal,
        OffsetDateTime createdTime,
        OffsetDateTime finalizedTime) {
}
