package com.example.starter.berth.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 靠泊窗口申请单实体，对应表 berth_window。
 *
 * @param id             主键
 * @param requestId      幂等请求键，由申请人提供，全库唯一
 * @param fingerprint    请求指纹：requestId 与全部业务字段经单元分隔符拼接后的 SHA-256 十六进制
 * @param vesselName     船名（合成数据）
 * @param berthCode      泊位代码
 * @param startAt        预计靠泊开始时刻（UTC），半开区间起点，含
 * @param endAt          预计靠泊结束时刻（UTC），半开区间终点，不含
 * @param draftMeters    船舶吃水（米）
 * @param pilotsRequired 所需引航员人数
 * @param status         窗口状态，见 {@link WindowStatus}
 * @param supersedesId   改期时被取代的原窗口 id；null 表示非改期申请
 * @param createdAt      创建时间（UTC）
 * @param updatedAt      最后更新时间（UTC）
 */
public record BerthWindow(
        long id,
        String requestId,
        String fingerprint,
        String vesselName,
        String berthCode,
        Instant startAt,
        Instant endAt,
        BigDecimal draftMeters,
        int pilotsRequired,
        WindowStatus status,
        Long supersedesId,
        Instant createdAt,
        Instant updatedAt) {
}
