package com.example.starter.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 受控分段指令登记结果。replayed=true 表示同键同参重放，内容与首次冻结结果一致。
 */
public record ControlledOrderReceiveResponse(
        long orderId,
        String orderNo,
        String requestId,
        String farmCode,
        int targetPowerKw,
        int farmParticipatingKw,
        int curtailedKw,
        String status,
        int planVersion,
        Instant startAt,
        int durationMinutes,
        Long planVersionId,
        Integer planVersionNo,
        LocalDate planDate,
        Integer segmentIndex,
        List<ControlledTurbinePlanItem> turbines,
        boolean replayed) implements ReplayableResponse<ControlledOrderReceiveResponse> {

    @Override
    public ControlledOrderReceiveResponse asReplay() {
        return new ControlledOrderReceiveResponse(orderId, orderNo, requestId, farmCode,
                targetPowerKw, farmParticipatingKw, curtailedKw, status, planVersion, startAt,
                durationMinutes, planVersionId, planVersionNo, planDate, segmentIndex,
                turbines, true);
    }
}
