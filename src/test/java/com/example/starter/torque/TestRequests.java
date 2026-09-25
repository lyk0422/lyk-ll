package com.example.starter.torque;

import com.example.starter.torque.dto.DispatchRequest;
import com.example.starter.torque.dto.InspectionRequest;
import com.example.starter.torque.dto.ReleaseRequest;
import com.example.starter.torque.dto.TighteningRequest;
import com.example.starter.torque.dto.ToolRegisterRequest;
import com.example.starter.torque.dto.WitnessRequest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 测试请求构造工厂。 */
final class TestRequests {

    private TestRequests() {
    }

    static DispatchRequest dispatch(String requestId, String cardNo, String station,
                                    List<DispatchRequest.FastenerSpecDto> fasteners) {
        return new DispatchRequest(requestId, cardNo, station, "BATCH-001", fasteners);
    }

    static DispatchRequest.FastenerSpecDto spec(String pointCode, String nominal, boolean hPoint) {
        return new DispatchRequest.FastenerSpecDto(pointCode, new BigDecimal(nominal), hPoint);
    }

    static TighteningRequest tighten(String requestId, String pointCode, String torque,
                                     String toolNo, String operator) {
        return new TighteningRequest(requestId, pointCode, new BigDecimal(torque), toolNo, operator);
    }

    static InspectionRequest self(String requestId, String inspector) {
        return new InspectionRequest(requestId, "SELF", inspector);
    }

    static InspectionRequest mutual(String requestId, String inspector) {
        return new InspectionRequest(requestId, "MUTUAL", inspector);
    }

    static WitnessRequest absent(String requestId, String pointCode) {
        return new WitnessRequest(requestId, pointCode, false, null);
    }

    static WitnessRequest present(String requestId, String pointCode, String witness) {
        return new WitnessRequest(requestId, pointCode, true, witness);
    }

    static ReleaseRequest release(String requestId) {
        return new ReleaseRequest(requestId);
    }

    static ToolRegisterRequest tool(String toolNo, LocalDate lastCheckUtcDate) {
        return new ToolRegisterRequest(toolNo, lastCheckUtcDate);
    }
}
