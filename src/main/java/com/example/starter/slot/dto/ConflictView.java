package com.example.starter.slot.dto;

/**
 * 整周冲突明细：同一机场、同一时刻、同一周已被其他航司占用。
 *
 * @param airport         机场三字码
 * @param slotTime        冲突时刻 HH:mm
 * @param week            冲突周序号
 * @param holderAirline   当前占用航司
 * @param holderFlightNo  当前占用航班号
 * @param holderRequestId 当前占用系列的申请幂等键
 */
public record ConflictView(
        String airport,
        String slotTime,
        Integer week,
        String holderAirline,
        String holderFlightNo,
        String holderRequestId) {
}
