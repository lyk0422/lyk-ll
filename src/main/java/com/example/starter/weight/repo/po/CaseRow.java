package com.example.starter.weight.repo.po;

import java.time.Instant;

/**
 * 治超案件行。
 *
 * @param id               主键
 * @param caseNo           案件编号
 * @param plateNo          车牌号
 * @param stationCode      检测站编码
 * @param weighTime        过磅时刻（UTC）
 * @param status           案件状态（OPEN/CLOSED）
 * @param closedAt         结案时间（UTC）；未结案为 null
 * @param closeRequestId   结案幂等请求标识；未结案为 null
 * @param closeFingerprint 结案幂等指纹；未结案为 null
 * @param createdAt        立案时间（UTC）
 */
public record CaseRow(
        Long id,
        String caseNo,
        String plateNo,
        String stationCode,
        Instant weighTime,
        String status,
        Instant closedAt,
        String closeRequestId,
        String closeFingerprint,
        Instant createdAt) {
}
