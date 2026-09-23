package com.example.starter.spectrum.api;

import com.example.starter.spectrum.domain.LeaseAggregate;

import java.util.List;

/**
 * 租约聚合到 API 视图的转换。
 */
public final class LeaseViews {

    private LeaseViews() {
    }

    /**
     * 转换为租约响应视图。
     */
    public static LeaseResponse toResponse(LeaseAggregate aggregate) {
        List<LeaseResponse.EventView> events = aggregate.events().stream()
                .map(e -> new LeaseResponse.EventView(
                        e.version(), e.eventType(), e.detail(), e.createdAtUtc()))
                .toList();
        return new LeaseResponse(
                aggregate.leaseId(), aggregate.currentVersion(), aggregate.status(),
                aggregate.regions(), aggregate.start(), aggregate.end(),
                aggregate.freqLowMHz(), aggregate.freqHighMHz(), aggregate.mode(),
                aggregate.eirpMw(), aggregate.cancelledAtUtc(), events);
    }
}
