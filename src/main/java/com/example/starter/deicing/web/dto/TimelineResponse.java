package com.example.starter.deicing.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * 全部车位占用时间线响应。
 */
public record TimelineResponse(Instant generatedAt, List<BayTimelineView> bays) {
}
