package com.example.starter.battery.web;

import java.util.List;

import com.example.starter.battery.Battery;
import com.example.starter.battery.BatteryEvent;
import com.example.starter.battery.Transfer;

/**
 * API 响应体定义。
 */
public final class Responses {

    private Responses() {
    }

    /** 电池台账视图。 */
    public record BatteryResponse(
            long id,
            String code,
            String status,
            String stationId,
            String vehicleId,
            Integer soc,
            Integer health,
            Long inspectionBatch) {

        public static BatteryResponse from(Battery b) {
            return new BatteryResponse(b.id(), b.code(), b.status().name(), b.stationId(),
                    b.vehicleId(), b.soc(), b.health(), b.inspectionBatch());
        }
    }

    /** 入站结果。 */
    public record InboundResponse(String code, String status, String stationId, long eventSeq) {
    }

    /** 检测结果。 */
    public record InspectionResponse(String code, long batch, int soc, int health,
                                     boolean passed, String status) {
    }

    /** 换出结果。 */
    public record SwapOutResponse(String code, String vehicleId, String status, long eventSeq) {
    }

    /** 调拨单视图。 */
    public record TransferResponse(
            long transferId,
            String code,
            String fromStation,
            String toStation,
            String status,
            long initiatedAt,
            Long signedAt,
            Long exceptionAt,
            Long writtenOffAt) {

        public static TransferResponse from(Transfer t) {
            return new TransferResponse(t.id(), t.batteryCode(), t.fromStation(), t.toStation(),
                    t.status().name(), t.initiatedAt(), t.signedAt(), t.exceptionAt(),
                    t.writtenOffAt());
        }
    }

    /** 事件视图。 */
    public record EventResponse(long seq, String eventType, String payload,
                                String prevHash, String hash, long createdAt) {

        public static EventResponse from(BatteryEvent e) {
            return new EventResponse(e.seq(), e.eventType().name(), e.payload(),
                    e.prevHash(), e.hash(), e.createdAt());
        }
    }

    /** 事件链验真结果。 */
    public record VerifyResponse(String code, int eventCount, boolean valid) {
    }

    /** 事件链视图。 */
    public record ChainResponse(String code, List<EventResponse> events) {
    }
}
