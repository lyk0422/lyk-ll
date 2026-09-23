package com.example.starter.web.dto;

import com.example.starter.domain.CustodySegment;
import com.example.starter.domain.TemperatureReading;

import java.util.List;

/**
 * 分段证据：一段运输的保管信息、判定状态及覆盖序号范围内的全部读数。
 */
public record SegmentEvidence(
        int version,
        String custodian,
        long startTime,
        Long endTime,
        Long firstSequence,
        Long lastSequence,
        String status,
        List<ReadingResponse> readings
) {

    /**
     * 由段与其读数列表组装证据。
     */
    public static SegmentEvidence from(CustodySegment segment, List<TemperatureReading> readings) {
        return new SegmentEvidence(
                segment.version(),
                segment.custodian(),
                segment.startTime(),
                segment.endTime(),
                segment.firstSequence(),
                segment.lastSequence(),
                segment.status().name(),
                readings.stream().map(r -> ReadingResponse.from(r, false)).toList()
        );
    }
}
