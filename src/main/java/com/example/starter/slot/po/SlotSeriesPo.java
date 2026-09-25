package com.example.starter.slot.po;

/**
 * 时刻系列申请记录。
 *
 * @param id        主键
 * @param requestId 申请幂等键
 * @param season    航季标识
 * @param airline   航司二字码
 * @param flightNo  航班号
 * @param airport   机场三字码
 * @param slotTime  申请时刻 HH:mm
 * @param weeksCsv  覆盖周列表规范化表示（升序、去重、逗号分隔）
 * @param fingerprint 申请指纹：requestId + 航班号 + 时刻 + 全部覆盖周
 * @param decision  裁决依据 RETAINED / POOL
 * @param status    系列状态 HELD / RELEASED
 * @param version   乐观锁版本号
 * @param createdAt 创建时间（epoch 毫秒，UTC）
 */
public record SlotSeriesPo(
        Long id,
        String requestId,
        String season,
        String airline,
        String flightNo,
        String airport,
        String slotTime,
        String weeksCsv,
        String fingerprint,
        String decision,
        String status,
        long version,
        long createdAt) {
}
