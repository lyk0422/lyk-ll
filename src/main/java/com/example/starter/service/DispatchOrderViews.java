package com.example.starter.service;

import com.example.starter.model.DispatchOrder;
import com.example.starter.web.dto.ApiDtos.DispatchOrderView;

/**
 * 调度单实体到 API 视图的映射。
 */
final class DispatchOrderViews {

    private DispatchOrderViews() {
    }

    /**
     * 实体转视图。
     */
    static DispatchOrderView toView(DispatchOrder order) {
        return new DispatchOrderView(order.id(), order.requestId(), order.stationCode(),
                order.generatorCode(), order.status().name(), order.supportHours(),
                order.deadlineAt(), order.assignedAt(), order.confirmedAt(), order.closedAt());
    }
}
