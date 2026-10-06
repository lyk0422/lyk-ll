package com.example.starter.web.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.example.starter.domain.PilotPlanEntity;

/**
 * 引航计划响应。
 *
 * @param id            计划ID
 * @param planNo        计划编号
 * @param shipName      船舶名称
 * @param voyageNo      航次号
 * @param status        计划状态
 * @param pilotId       引航员ID，未编排为空
 * @param tugboatIds    拖轮组合，未编排为空列表
 * @param tideStart     潮水窗口开始（含），未编排为空
 * @param tideEnd       潮水窗口结束（不含），未编排为空
 * @param breachCount   该计划关联的合成违约记录数
 */
public record PlanResponse(
        long id,
        String planNo,
        String shipName,
        String voyageNo,
        String status,
        Long pilotId,
        List<Long> tugboatIds,
        LocalDateTime tideStart,
        LocalDateTime tideEnd,
        int breachCount) {

    public static PlanResponse of(PilotPlanEntity entity, List<Long> tugboatIds, int breachCount) {
        return new PlanResponse(
                entity.id(),
                entity.planNo(),
                entity.shipName(),
                entity.voyageNo(),
                entity.status().name(),
                entity.pilotId(),
                tugboatIds,
                entity.tideStart(),
                entity.tideEnd(),
                breachCount);
    }
}
