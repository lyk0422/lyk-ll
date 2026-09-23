package com.example.starter.coldchain.dto;

import com.example.starter.coldchain.domain.SegmentStatus;
import com.example.starter.coldchain.entity.TemperatureReading;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 分段证据查询结果：段元数据 + 段内读数（按序号升序）。
 *
 * @param version        段版本号
 * @param custodian      保管方
 * @param startTime      段开始时间
 * @param endTime        段关闭时间；开放段为 {@code null}
 * @param firstSequence  首序号（含）
 * @param lastSequence   末序号（含）；开放段为 {@code null}
 * @param status         段状态
 * @param readings       段内读数证据（按序号升序）
 */
public record SegmentEvidenceResponse(
        int version,
        String custodian,
        LocalDateTime startTime,
        LocalDateTime endTime,
        long firstSequence,
        Long lastSequence,
        SegmentStatus status,
        List<ReadingView> readings
) {

    /**
     * 证据中的单条读数视图。
     */
    public record ReadingView(
            long sequenceNo,
            String deviceNo,
            LocalDateTime sampledAt,
            double temperature
    ) {

        public static ReadingView from(TemperatureReading r) {
            return new ReadingView(r.sequenceNo(), r.deviceNo(), r.sampledAt(), r.temperature());
        }
    }
}
