package com.example.starter.slot.dto;

import java.util.List;

/**
 * 系列申请裁决结果。
 *
 * @param requestId   幂等键
 * @param decision    裁决依据 RETAINED / POOL
 * @param status      系列当前状态 HELD / RELEASED
 * @param allocations 本系列全部覆盖周的占用明细；退回后为空
 * @param replay      是否为同键同参的幂等重放
 */
public record SlotApplicationResult(
        String requestId,
        String decision,
        String status,
        List<AllocationView> allocations,
        boolean replay) {
}
