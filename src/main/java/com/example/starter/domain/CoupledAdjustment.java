package com.example.starter.domain;

import java.time.Instant;

/**
 * 联调记录：全组目标开度的一次原子提交，失败整组零变化不占幂等键。
 *
 * @param id           主键
 * @param groupId      所属联调组 ID
 * @param stationId    所属换热站 ID（冗余自联调组）
 * @param groupVersion 提交时核对的联调组版本，成功后组版本加 1
 * @param requestedAt  请求携带的 UTC 时刻，参与幂等指纹
 * @param requestId    客户端幂等键，全局唯一
 * @param fingerprint  幂等指纹：requestId、全阀集合、权重与 UTC 时刻的结构化 SHA-256
 * @param createdAt    联调执行时刻（UTC）
 */
public record CoupledAdjustment(long id, long groupId, long stationId, int groupVersion,
                                Instant requestedAt, String requestId, String fingerprint,
                                Instant createdAt) {
}
