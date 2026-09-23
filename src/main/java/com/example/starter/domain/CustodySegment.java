package com.example.starter.domain;

/**
 * 运输保管段。
 *
 * @param id             自增主键
 * @param shipmentId     所属货件
 * @param version        段序号（从 0 开始，货件内唯一）
 * @param custodian      保管方
 * @param startTime      段开始时间，Unix 毫秒时间戳（UTC）
 * @param endTime        段关闭时间；{@code null} 表示仍打开
 * @param firstSequence  段内已收最小设备序号（闭区间起点）；打开且尚无读数时为 {@code null}
 * @param lastSequence   段内末设备序号（打开时为当前已收最大序号）；无读数时为 {@code null}
 * @param status         段状态
 */
public record CustodySegment(
        Long id,
        String shipmentId,
        int version,
        String custodian,
        long startTime,
        Long endTime,
        Long firstSequence,
        Long lastSequence,
        SegmentStatus status
) {
}
