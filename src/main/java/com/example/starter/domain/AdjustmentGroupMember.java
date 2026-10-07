package com.example.starter.domain;

import java.time.Instant;

/**
 * 联调组成员：建组时冻结的阀门权重与开度版本，组解除后仅作历史保留。
 *
 * @param id                主键
 * @param groupId           所属联调组 ID
 * @param valveId           成员阀门 ID，同一活动组内唯一，且只能属于一个活动组
 * @param weight            阀门权重，正整数 1~10，建组时冻结
 * @param frozenValveVersion 建组时冻结的阀门开度版本（无版本为 0）
 * @param createdAt         成员加入时刻（UTC）
 */
public record AdjustmentGroupMember(long id, long groupId, long valveId, int weight,
                                    int frozenValveVersion, Instant createdAt) {
}
