package com.example.starter.museum.api;

import java.time.Instant;
import java.util.List;

/** 借展历史复算视图：合同轨迹 + 越限轨迹 + 处置时限。 */
public record LoanHistoryResponse(
        ContractResponse contract,
        List<EventView> events,
        List<AlertResponse> alerts,
        List<WorkOrderResponse> workOrders
) {
    public record EventView(String type, String detail, Instant at) {
    }
}
