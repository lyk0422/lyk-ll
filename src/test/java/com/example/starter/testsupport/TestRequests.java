package com.example.starter.testsupport;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.example.starter.api.dto.CollectionBatchRequest;
import com.example.starter.api.dto.CollectionCloseRequest;
import com.example.starter.api.dto.CollectionPacketRequest;
import com.example.starter.api.dto.CollectionSessionRequest;
import com.example.starter.api.dto.CoverageRequest;
import com.example.starter.api.dto.DispatchRequest;
import com.example.starter.api.dto.InspectionRequest;
import com.example.starter.api.dto.ReleaseRequest;
import com.example.starter.api.dto.ToolRegisterRequest;
import com.example.starter.api.dto.TorqueRequest;
import com.example.starter.api.dto.WitnessRequest;

/**
 * 测试请求构造工厂，统一合成数据。
 */
public final class TestRequests {

    public static final LocalDate TODAY = LocalDate.parse("2026-09-26");
    /** 采集包合成测量时刻基准：2026-09-26T08:00:00Z（当日 UTC，工具日校验有效）。 */
    public static final Instant MEASURE_BASE = Instant.parse("2026-09-26T08:00:00Z");

    private TestRequests() {
    }

    public static DispatchRequest dispatch(String requestId, String cardNo, String station, String witnessPoint) {
        return new DispatchRequest(requestId, cardNo, station, "SN-" + cardNo, witnessPoint,
                "operator-A", List.of(
                        new DispatchRequest.FastenerSpec("P1", new BigDecimal("100.000"), null),
                        new DispatchRequest.FastenerSpec("P2", new BigDecimal("50.000"), null)));
    }

    public static DispatchRequest singlePoint(String requestId, String cardNo, String station,
                                              String witnessPoint, String point, String nominal) {
        return new DispatchRequest(requestId, cardNo, station, "SN-" + cardNo, witnessPoint,
                "operator-A", List.of(
                        new DispatchRequest.FastenerSpec(point, new BigDecimal(nominal), null)));
    }

    public static ToolRegisterRequest tool(String toolCode, LocalDate calibrationDate) {
        return new ToolRegisterRequest(toolCode, calibrationDate);
    }

    public static TorqueRequest torque(String requestId, String cardNo, String point,
                                       String measured, String operator, String toolCode) {
        return new TorqueRequest(requestId, cardNo, point, new BigDecimal(measured), operator, toolCode);
    }

    public static InspectionRequest inspection(String requestId, String cardNo,
                                               String self, String mutual) {
        return new InspectionRequest(requestId, cardNo, self, mutual);
    }

    public static WitnessRequest witnessPresent(String requestId, String cardNo, String witnessName) {
        return new WitnessRequest(requestId, cardNo, "PRESENT", witnessName);
    }

    public static WitnessRequest witnessAbsent(String requestId, String cardNo) {
        return new WitnessRequest(requestId, cardNo, "ABSENT", null);
    }

    public static CoverageRequest coverage(String requestId, String cardNo) {
        return new CoverageRequest(requestId, cardNo, "COVER-SEAL");
    }

    public static ReleaseRequest release(String requestId, String cardNo, String releasedBy) {
        return new ReleaseRequest(requestId, cardNo, releasedBy);
    }

    // ------------------------------------------------------------------
    // 本地力矩采集会话
    // ------------------------------------------------------------------

    /** 创建采集会话请求，expectedVersion=1（新建必须传 1）。 */
    public static CollectionSessionRequest session(String requestId, String sessionNo, String cardNo,
                                                   String toolCode, int expectedCount, List<String> points) {
        return new CollectionSessionRequest(requestId, 1, sessionNo, cardNo, toolCode, expectedCount, points);
    }

    /** 采集包：测量时刻为基准 + seq 分钟，力矩为合成值。 */
    public static CollectionPacketRequest packet(int seq, String point, String torque) {
        return new CollectionPacketRequest(seq, point, new BigDecimal(torque),
                MEASURE_BASE.plusSeconds(seq * 60L));
    }

    /** 指定测量时刻的采集包（用于跨 UTC 日界的工具校验失败场景）。 */
    public static CollectionPacketRequest packetAt(int seq, String point, String torque, Instant measuredAt) {
        return new CollectionPacketRequest(seq, point, new BigDecimal(torque), measuredAt);
    }

    public static CollectionBatchRequest batch(String requestId, String sessionNo, int expectedVersion,
                                               List<CollectionPacketRequest> packets) {
        return new CollectionBatchRequest(requestId, sessionNo, expectedVersion, packets);
    }

    public static CollectionCloseRequest close(String requestId, String sessionNo, int expectedVersion) {
        return new CollectionCloseRequest(requestId, sessionNo, expectedVersion);
    }
}
