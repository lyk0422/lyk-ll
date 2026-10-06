package com.example.starter.domain;

import java.time.LocalDateTime;

/**
 * 复测记录，与限功率解除同事务原子提交。
 *
 * @param id            主键
 * @param transmitterId 复测发射机主键
 * @param directiveId   关联指令主键
 * @param actualPowerW  实测功率，单位 W
 * @param limitW        复测时执行限值，单位 W
 * @param result        结论：CLEAR=无干扰
 * @param measuredAt    复测采样时刻（UTC）
 * @param recordedBy    记录人
 * @param createdAt     落库时刻（UTC）
 */
public record RetestRecord(long id, long transmitterId, long directiveId, int actualPowerW, int limitW,
                           String result, LocalDateTime measuredAt, String recordedBy, LocalDateTime createdAt) {

    public static final String RESULT_CLEAR = "CLEAR";
}
