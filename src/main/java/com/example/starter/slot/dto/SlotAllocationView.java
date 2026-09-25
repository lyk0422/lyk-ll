package com.example.starter.slot.dto;

/**
 * 单条时刻占用记录视图。
 *
 * @param slotTime 时刻（HH:mm）
 * @param week     周序号
 * @param type     占用类型：RETAINED（历史优先权保留）或 POOL（公共池分配）
 */
public record SlotAllocationView(String slotTime, int week, String type) {
}
