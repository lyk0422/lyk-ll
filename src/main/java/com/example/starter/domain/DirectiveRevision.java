package com.example.starter.domain;

import java.time.LocalDateTime;

/**
 * 功率指令轨迹，支撑事件与指令全程可复算。
 *
 * @param id          主键
 * @param directiveId 所属指令主键
 * @param eventId     关联事件主键；null=与具体事件无关（如解除）
 * @param changeType  轨迹类型：ISSUE/TIGHTEN/RELAX/LIFT
 * @param limitWAfter 变更后限值，单位 W
 * @param createdAt   变更时刻（UTC）
 * @param note        变更说明
 */
public record DirectiveRevision(long id, long directiveId, Long eventId, String changeType,
                                int limitWAfter, LocalDateTime createdAt, String note) {

    public static final String TYPE_ISSUE = "ISSUE";
    public static final String TYPE_TIGHTEN = "TIGHTEN";
    public static final String TYPE_RELAX = "RELAX";
    public static final String TYPE_LIFT = "LIFT";
}
