package com.example.starter.slot.dto;

import java.util.List;

/**
 * 系列申请结果。
 *
 * @param requestId   申请幂等键
 * @param airline     航司二字码
 * @param flightNo    航班号
 * @param status      结果状态：ALLOCATED（全部分配成功）
 * @param allocations 已占用的时刻/周清单
 */
public record SlotResultView(String requestId, String airline, String flightNo,
                             String status, List<SlotAllocationView> allocations) {
}
