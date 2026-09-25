package com.example.starter.slot.dto;

import java.util.List;

/**
 * 系列退回结果。
 *
 * @param requestId 申请幂等键
 * @param released  本次释放的时刻/周清单
 */
public record ReturnResultView(String requestId, List<SlotAllocationView> released) {
}
