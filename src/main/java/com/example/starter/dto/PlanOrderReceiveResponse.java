package com.example.starter.dto;

import java.util.List;

/**
 * 计划绑定模式限电指令接收/重放响应。
 * 在普通指令响应基础上携带绑定版本与受理时冻结的机组基数证据；
 * replayed=true 表示同键同参重放，内容与首次响应一致。
 */
public record PlanOrderReceiveResponse(
        Long orderId,
        String orderNo,
        String requestId,
        String farmCode,
        int targetPowerKw,
        int participatingTotalKw,
        int curtailedKw,
        String status,
        int cycleNo,
        Long planId,
        String planDate,
        int versionNo,
        int segmentIndex,
        List<PlanBasisItem> basis,
        boolean replayed) {

    /** 同键同参重放时标记 replayed=true，其余内容与首次响应一致。 */
    public PlanOrderReceiveResponse asReplay() {
        return new PlanOrderReceiveResponse(orderId, orderNo, requestId, farmCode, targetPowerKw,
                participatingTotalKw, curtailedKw, status, cycleNo, planId, planDate,
                versionNo, segmentIndex, basis, true);
    }
}
