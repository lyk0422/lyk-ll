package com.example.starter.slot.po;

/**
 * 已发布分配表的快照条目；发布后不再随实时占用变化。
 *
 * @param id        主键
 * @param season    航季标识
 * @param airport   机场三字码
 * @param slotTime  时刻 HH:mm
 * @param week      航季内周序号
 * @param airline   占用航司
 * @param flightNo  占用航班号
 * @param requestId 所属系列申请幂等键
 * @param basis     占用依据 RETAINED / POOL
 */
public record SnapshotAllocationPo(
        Long id,
        String season,
        String airport,
        String slotTime,
        Integer week,
        String airline,
        String flightNo,
        String requestId,
        String basis) {
}
