package com.example.starter.web.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 引航计划响应视图。
 *
 * @param violationCreated 本次取消是否合成了违约记录（仅取消操作可能为 true）
 */
public record PlanResponse(
        Long planId,
        String status,
        Long pilotId,
        List<Long> tugIds,
        LocalDateTime planStart,
        LocalDateTime planEnd,
        LocalDateTime tideStart,
        LocalDateTime tideEnd,
        boolean violationCreated) {
}
