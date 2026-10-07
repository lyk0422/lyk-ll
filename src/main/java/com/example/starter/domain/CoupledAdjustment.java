package com.example.starter.domain;

import java.time.Instant;

/**
 * 联调单：全组目标开度的原子提交记录，失败全组零变化。
 *
 * @param id          主键
 * @param groupId     所属联调组 ID
 * @param stationId   所属换热站 ID（冗余自联调组，便于锁定）
 * @param requestId   客户端幂等键，全局唯一；失败不占键
 * @param fingerprint 幂等指纹：requestId、全阀集合、权重、逐阀目标/版本/解封单与 UTC 时刻的结构化 SHA-256
 * @param adjustAt    客户端声明的联调时刻（UTC），参与幂等指纹
 * @param executedAt  联调实际执行时刻（UTC），逐阀任务稳定期起点
 * @param version     联调单版本，从 1 开始
 * @param createdAt   联调单受理时刻（UTC）
 */
public record CoupledAdjustment(long id, long groupId, long stationId, String requestId,
                                String fingerprint, Instant adjustAt, Instant executedAt,
                                int version, Instant createdAt) {
}
