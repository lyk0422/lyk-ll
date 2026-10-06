package com.example.starter.domain;

import java.time.LocalDateTime;

/**
 * 引航计划实体，对应表 pilot_plan。
 *
 * @param id           主键
 * @param planNo       计划编号，业务唯一
 * @param shipName     船舶名称
 * @param voyageNo     航次号
 * @param status       计划状态
 * @param pilotId      引航员ID，编排前为空
 * @param tideStart    潮水窗口开始（含），编排前为空
 * @param tideEnd      潮水窗口结束（不含），编排前为空
 * @param appliedAt    申请时间
 * @param confirmedAt  确认时间，未确认为空
 * @param startedAt    开始执行时间，未执行为空
 * @param settledAt    核销时间，未核销为空
 * @param cancelledAt  取消时间，未取消为空
 * @param cancelReason 取消原因，未取消为空
 * @param version      乐观锁版本号
 */
public record PilotPlanEntity(
        long id,
        String planNo,
        String shipName,
        String voyageNo,
        PlanStatus status,
        Long pilotId,
        LocalDateTime tideStart,
        LocalDateTime tideEnd,
        LocalDateTime appliedAt,
        LocalDateTime confirmedAt,
        LocalDateTime startedAt,
        LocalDateTime settledAt,
        LocalDateTime cancelledAt,
        String cancelReason,
        int version) {
}
