package com.example.starter.web.dto;

import java.util.List;

/**
 * 货件全量分段证据。
 */
public record EvidenceResponse(
        ShipmentResponse shipment,
        List<SegmentEvidence> segments
) {
}
