package com.example.starter.domain;

import java.time.Instant;
import java.util.List;

/**
 * 靠泊窗口申请记录。时间均为 UTC Instant，窗口为半开区间 [startAt, endAt)。
 *
 * @param id             窗口主键
 * @param requestId      幂等请求键
 * @param fingerprint    请求指纹（覆盖 requestId 与全部业务字段）
 * @param vesselName     船名
 * @param berthId        泊位主键
 * @param startAt        预计靠泊开始（含）
 * @param endAt          预计靠泊结束（不含）
 * @param draftM         吃水（米）
 * @param pilotsRequired 所需引航员数
 * @param status         窗口状态
 * @param pilotIds       已分配引航员（仅批准/生效窗口非空）
 * @param createdAt      创建时间
 * @param updatedAt      最近变更时间
 */
public record BerthWindow(
        long id,
        String requestId,
        String fingerprint,
        String vesselName,
        long berthId,
        Instant startAt,
        Instant endAt,
        double draftM,
        int pilotsRequired,
        WindowStatus status,
        List<Long> pilotIds,
        Instant createdAt,
        Instant updatedAt) {
}
