package com.example.starter.domain;

/**
 * 联调组成员：建组/改组时冻结的阀门权重与开度版本。
 *
 * @param id           主键
 * @param groupId      所属联调组 ID
 * @param valveId      成员阀门 ID，组内唯一；活动组间不重复
 * @param weight       联调权重，正整数 1~10，建组时冻结
 * @param valveVersion 建组/改组时冻结的阀门开度版本（无版本为 0）
 */
public record CoupledGroupMember(long id, long groupId, long valveId, int weight, int valveVersion) {
}
