package com.example.starter.elevator;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 电梯台账实体。
 *
 * @param id               主键
 * @param code             电梯编号，全局唯一
 * @param status           运行状态：RUNNING 运行中，STOPPED 已停梯
 * @param keySupervision   重点监察标记（维保逾期触发）
 * @param stopCaseId       触发强制停梯的救援单ID；非案件停梯（如维保逾期锁定）为 null
 * @param lastMaintainedOn 最近一次维保完成日期（UTC 日界），维保周期 15 日由此起算
 * @param version          乐观锁版本号
 * @param createdAt        创建时间（UTC）
 */
public record Elevator(
        Long id,
        String code,
        String status,
        boolean keySupervision,
        Long stopCaseId,
        LocalDate lastMaintainedOn,
        long version,
        Instant createdAt) {

    /** 运行中。 */
    public static final String STATUS_RUNNING = "RUNNING";
    /** 已停梯。 */
    public static final String STATUS_STOPPED = "STOPPED";
}
