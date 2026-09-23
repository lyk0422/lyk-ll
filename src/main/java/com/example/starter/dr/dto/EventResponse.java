package com.example.starter.dr.dto;

import java.time.Instant;

/**
 * 事件视图。
 *
 * @param id              事件 ID
 * @param eventRef        事件业务编码
 * @param startUtc        起始时刻 UTC（含）
 * @param endUtc          结束时刻 UTC（不含）
 * @param intervalMinutes 区间粒度（分钟）
 * @param intervalCount   区间总数
 * @param version         事件聚合版本号
 * @param frozen          是否已冻结
 */
public record EventResponse(
        Long id,
        String eventRef,
        Instant startUtc,
        Instant endUtc,
        int intervalMinutes,
        int intervalCount,
        int version,
        boolean frozen) {
}
