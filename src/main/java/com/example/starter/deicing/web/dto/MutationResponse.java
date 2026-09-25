package com.example.starter.deicing.web.dto;

import java.util.List;

/**
 * 申请/出队/作业等写操作的统一响应。
 *
 * @param replay 是否为幂等重放（返回首次结果）
 * @param bays   出队后实际分配的车位编号列表
 */
public record MutationResponse(
        QueueItemView item,
        boolean replay,
        List<String> bays) {

    public static MutationResponse of(QueueItemView item, boolean replay, List<String> bays) {
        return new MutationResponse(item, replay, bays == null ? List.of() : List.copyOf(bays));
    }
}
