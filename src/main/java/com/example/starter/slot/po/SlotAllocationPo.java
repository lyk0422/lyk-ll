package com.example.starter.slot.po;

/**
 * 时刻占用记录：同一机场、同一时刻、同一周全表至多一条存活占用（唯一索引保证）。
 *
 * @param id        主键
 * @param season    航季标识
 * @param airport   机场三字码
 * @param slotTime  时刻 HH:mm（5 分钟粒度）
 * @param week      航季内周序号
 * @param airline   占用航司二字码
 * @param flightNo  占用航班号
 * @param requestId 占用所属系列的申请幂等键
 * @param basis     占用依据 RETAINED / POOL
 * @param active    存活标记：1 占用中，0 已退回释放
 */
public record SlotAllocationPo(
        Long id,
        String season,
        String airport,
        String slotTime,
        Integer week,
        String airline,
        String flightNo,
        String requestId,
        String basis,
        int active) {
}
