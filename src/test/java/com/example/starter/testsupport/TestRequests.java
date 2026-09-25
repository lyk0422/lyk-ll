package com.example.starter.testsupport;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

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
}
